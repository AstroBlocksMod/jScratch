package org.jscratch.compiler.codegen

import org.jscratch.compiler.sb3.Block
import org.jscratch.compiler.sb3.Input
import org.jscratch.compiler.sb3.PrimitiveType
import org.jscratch.compiler.sb3.Target
import org.objectweb.asm.Label
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes.ACC_FINAL
import org.objectweb.asm.Opcodes.ACC_PRIVATE
import org.objectweb.asm.Opcodes.ACC_PUBLIC
import org.objectweb.asm.Opcodes.ACC_STATIC
import org.objectweb.asm.Opcodes.ACC_SUPER
import org.objectweb.asm.Opcodes.ACONST_NULL
import org.objectweb.asm.Opcodes.ALOAD
import org.objectweb.asm.Opcodes.ARETURN
import org.objectweb.asm.Opcodes.ASTORE
import org.objectweb.asm.Opcodes.BIPUSH
import org.objectweb.asm.Opcodes.CHECKCAST
import org.objectweb.asm.Opcodes.DADD
import org.objectweb.asm.Opcodes.DCMPG
import org.objectweb.asm.Opcodes.DCMPL
import org.objectweb.asm.Opcodes.DCONST_0
import org.objectweb.asm.Opcodes.DCONST_1
import org.objectweb.asm.Opcodes.DDIV
import org.objectweb.asm.Opcodes.DMUL
import org.objectweb.asm.Opcodes.DSUB
import org.objectweb.asm.Opcodes.DUP
import org.objectweb.asm.Opcodes.DUP_X1
import org.objectweb.asm.Opcodes.GETFIELD
import org.objectweb.asm.Opcodes.GETSTATIC
import org.objectweb.asm.Opcodes.GOTO
import org.objectweb.asm.Opcodes.I2D
import org.objectweb.asm.Opcodes.IAND
import org.objectweb.asm.Opcodes.ICONST_0
import org.objectweb.asm.Opcodes.ICONST_1
import org.objectweb.asm.Opcodes.IFEQ
import org.objectweb.asm.Opcodes.IFGT
import org.objectweb.asm.Opcodes.IFLE
import org.objectweb.asm.Opcodes.IFLT
import org.objectweb.asm.Opcodes.IFNE
import org.objectweb.asm.Opcodes.IFNONNULL
import org.objectweb.asm.Opcodes.ILOAD
import org.objectweb.asm.Opcodes.INVOKESPECIAL
import org.objectweb.asm.Opcodes.INVOKESTATIC
import org.objectweb.asm.Opcodes.INVOKEVIRTUAL
import org.objectweb.asm.Opcodes.IOR
import org.objectweb.asm.Opcodes.ISTORE
import org.objectweb.asm.Opcodes.IXOR
import org.objectweb.asm.Opcodes.NEW
import org.objectweb.asm.Opcodes.POP
import org.objectweb.asm.Opcodes.PUTFIELD
import org.objectweb.asm.Opcodes.PUTSTATIC
import org.objectweb.asm.Opcodes.RETURN
import org.objectweb.asm.Opcodes.SIPUSH
import org.objectweb.asm.Opcodes.V21

/** Static type of a value on the JVM operand stack. */
enum class VType(val desc: String) {
    NUM("D"),
    STR(RT.D_STRING),
    BOOL("Z"),
    ANY(RT.D_OBJECT),
}

/** A custom block (procedure) of the target. */
class ProcInfo(
    val proccode: String,
    val method: String,
    val argumentIds: List<String>,
    val argumentNames: List<String>,
    val argumentDefaults: List<String>,
    val warp: Boolean,
    val definition: Block,
)

/** A hat block and the script method it starts. */
class ScriptInfo(val id: Int, val method: String, val hat: Block, val hatKind: Int, val hatArg: String)

/** Per-method emission state. */
class MethodState(val mv: MethodVisitor, val proc: ProcInfo?, var nextLocal: Int) {
    val argSlots = HashMap<String, Int>()

    fun allocLocal(size: Int): Int {
        val slot = nextLocal
        nextLocal += size
        return slot
    }
}

/**
 * Compiles one Scratch target (the stage or a sprite) into one JVM class that
 * extends the runtime's `Stage` or `Sprite`.
 *
 * Every hat script becomes a `void script_N()` method and every custom block a
 * `void proc_N(Object...)` method. Variables become fields. Scratch's dynamic
 * values are `Object` (Double, String or Boolean) at rest; expressions are
 * emitted with the static type that the block naturally produces and converted
 * at the use site, so arithmetic stays on primitive doubles.
 */
class TargetCompiler(private val ctx: ProjectContext, private val target: Target, private val className: String) {
    private val superName = if (target.isStage) RT.STAGE else RT.SPRITE
    private val cw = SafeClassWriter()
    private val fieldNames = Names()
    private val localSlots = HashMap<String, VarSlot>()
    private val localSlotsByName = HashMap<String, VarSlot>()
    private val localSlotList = ArrayList<VarSlot>()
    private val listInitResources = HashMap<String, String>()
    private val procs = HashMap<String, ProcInfo>()
    private val scripts = ArrayList<ScriptInfo>()

    fun compile(): ByteArray {
        collectVariables()
        collectProcedures()
        collectScripts()

        cw.visit(V21, ACC_PUBLIC or ACC_FINAL or ACC_SUPER, className, null, superName, null)
        cw.visitSource(target.name, null)
        emitFields()
        if (target.isStage) emitStaticInit()
        emitConstructor()
        emitRunScript()
        for (s in scripts) emitScript(s)
        for (p in procs.values) emitProcedure(p)
        if (!target.isStage) {
            emitNewInstance()
            emitCopyLocalsFrom()
        }
        emitLookupVariable()
        cw.visitEnd()
        return cw.toByteArray()
    }

    // ---- collection -----------------------------------------------------------------------------

    private fun collectVariables() {
        if (target.isStage) return
        for (v in target.variables.values) {
            val slot = VarSlot(className, fieldNames.unique("v_", v.name), false, false, v.name)
            localSlots[v.id] = slot
            localSlotsByName[v.name] = slot
            localSlotList.add(slot)
        }
        for (l in target.lists.values) {
            val slot = VarSlot(className, fieldNames.unique("l_", l.name), false, true, l.name)
            localSlots[l.id] = slot
            localSlotsByName["list:" + l.name] = slot
            localSlotList.add(slot)
        }
    }

    private fun collectProcedures() {
        val names = Names()
        for (b in target.blocks.values) {
            if (b.opcode != "procedures_definition") continue
            val protoRef = b.inputs["custom_block"] as? Input.BlockRef ?: continue
            val proto = target.blocks[protoRef.id] ?: continue
            val m = proto.mutation ?: continue
            if (procs.containsKey(m.proccode)) {
                ctx.warn("Duplicate custom block '${m.proccode}' in '${target.name}'; using the first definition")
                continue
            }
            procs[m.proccode] =
                ProcInfo(
                    proccode = m.proccode,
                    method = names.unique("proc_", m.proccode),
                    argumentIds = m.argumentIds,
                    argumentNames = m.argumentNames,
                    argumentDefaults = m.argumentDefaults,
                    warp = m.warp,
                    definition = b,
                )
        }
    }

    private fun collectScripts() {
        for (b in target.topLevelBlocks) {
            val (kind, arg) = hatKind(b) ?: continue
            scripts.add(ScriptInfo(scripts.size, "script_${scripts.size}", b, kind, arg))
        }
    }

    private fun hatKind(b: Block): Pair<Int, String>? = when (b.opcode) {
        "event_whenflagclicked" -> RT.HAT_FLAG to ""
        "event_whenkeypressed" -> RT.HAT_KEY to normalizeKey(b.fields["KEY_OPTION"]?.value ?: "any")
        "event_whenthisspriteclicked", "event_whenstageclicked" -> RT.HAT_CLICKED to ""
        "event_whenbroadcastreceived" -> RT.HAT_BROADCAST to (b.fields["BROADCAST_OPTION"]?.value ?: "")
        "event_whenbackdropswitchesto" -> RT.HAT_BACKDROP to (b.fields["BACKDROP"]?.value ?: "")
        "control_start_as_clone" -> RT.HAT_CLONE_START to ""
        "event_whengreaterthan", "event_whentouchingobject" -> {
            ctx.warn("Hat block '${b.opcode}' is not supported yet; its script never runs")
            null
        }
        else -> {
            null
        }
    }

    private fun normalizeKey(key: String): String = if (key.length == 1) key.uppercase() else key

    // ---- class members --------------------------------------------------------------------------

    private fun emitFields() {
        val slots = if (target.isStage) ctx.stageSlots else localSlotList
        for (s in slots) {
            val acc = ACC_PUBLIC or (if (s.isStatic) ACC_STATIC else 0)
            cw.visitField(acc, s.field, if (s.isList) RT.D_LIST else RT.D_OBJECT, null, null).visitEnd()
        }
    }

    private fun emitStaticInit() {
        val mv = cw.visitMethod(ACC_STATIC, "<clinit>", "()V", null, null)
        mv.visitCode()
        for (v in target.variables.values) {
            val slot = ctx.globalById(v.id) ?: continue
            pushConstant(mv, v.value)
            mv.visitFieldInsn(PUTSTATIC, className, slot.field, RT.D_OBJECT)
        }
        for (l in target.lists.values) {
            val slot = ctx.globalById(l.id) ?: continue
            emitNewList(mv, slot, l.items)
            mv.visitFieldInsn(PUTSTATIC, className, slot.field, RT.D_LIST)
        }
        mv.visitInsn(RETURN)
        mv.visitMaxs(0, 0)
        mv.visitEnd()
    }

    private fun emitNewList(mv: MethodVisitor, slot: VarSlot, items: List<Any>) {
        mv.visitTypeInsn(NEW, RT.LIST)
        mv.visitInsn(DUP)
        mv.visitMethodInsn(INVOKESPECIAL, RT.LIST, "<init>", "()V", false)
        if (items.isEmpty()) return
        if (items.size <= INLINE_LIST_LIMIT) {
            for (item in items) {
                mv.visitInsn(DUP)
                pushConstant(mv, item)
                mv.visitMethodInsn(INVOKEVIRTUAL, RT.LIST, "add", "(${RT.D_OBJECT})V", false)
            }
        } else {
            val path = ctx.addListResource(className, slot.field, items)
            mv.visitLdcInsn(path)
            mv.visitMethodInsn(INVOKEVIRTUAL, RT.LIST, "loadResource", "(${RT.D_STRING})${RT.D_LIST}", false)
        }
    }

    private fun emitConstructor() {
        val mv = cw.visitMethod(ACC_PUBLIC, "<init>", "(${RT.D_RUNTIME})V", null, null)
        mv.visitCode()
        mv.visitVarInsn(ALOAD, 0)
        mv.visitVarInsn(ALOAD, 1)
        mv.visitLdcInsn(target.name)
        mv.visitMethodInsn(INVOKESPECIAL, superName, "<init>", "(${RT.D_RUNTIME}${RT.D_STRING})V", false)

        fun putDouble(field: String, v: Double) {
            mv.visitVarInsn(ALOAD, 0)
            pushDouble(mv, v)
            mv.visitFieldInsn(PUTFIELD, RT.TARGET, field, "D")
        }

        fun putInt(field: String, v: Int) {
            mv.visitVarInsn(ALOAD, 0)
            pushInt(mv, v)
            mv.visitFieldInsn(PUTFIELD, RT.TARGET, field, "I")
        }

        fun putBool(field: String, v: Boolean) {
            mv.visitVarInsn(ALOAD, 0)
            mv.visitInsn(if (v) ICONST_1 else ICONST_0)
            mv.visitFieldInsn(PUTFIELD, RT.TARGET, field, "Z")
        }
        putDouble("x", target.x)
        putDouble("y", target.y)
        putDouble("direction", target.direction)
        putDouble("size", target.size)
        putDouble("volume", target.volume)
        putBool("visible", target.visible)
        putBool("draggable", target.draggable)
        putInt("layerOrder", target.layerOrder)
        putInt("currentCostume", target.currentCostume.coerceIn(0, Math.max(0, target.costumes.size - 1)))
        mv.visitVarInsn(ALOAD, 0)
        mv.visitLdcInsn(target.rotationStyle)
        mv.visitMethodInsn(INVOKEVIRTUAL, RT.TARGET, "setRotationStyle", "(${RT.D_STRING})V", false)

        for (c in target.costumes) {
            mv.visitVarInsn(ALOAD, 0)
            mv.visitLdcInsn(c.name)
            mv.visitLdcInsn(c.md5ext)
            pushDouble(mv, c.rotationCenterX)
            pushDouble(mv, c.rotationCenterY)
            pushDouble(mv, c.bitmapResolution)
            mv.visitMethodInsn(INVOKEVIRTUAL, RT.TARGET, "addCostume", "(${RT.D_STRING}${RT.D_STRING}DDD)V", false)
        }
        for (s in target.sounds) {
            mv.visitVarInsn(ALOAD, 0)
            mv.visitLdcInsn(s.name)
            mv.visitLdcInsn(s.md5ext)
            mv.visitMethodInsn(INVOKEVIRTUAL, RT.TARGET, "addSound", "(${RT.D_STRING}${RT.D_STRING})V", false)
        }

        if (!target.isStage) {
            for (v in target.variables.values) {
                val slot = localSlots.getValue(v.id)
                mv.visitVarInsn(ALOAD, 0)
                pushConstant(mv, v.value)
                mv.visitFieldInsn(PUTFIELD, className, slot.field, RT.D_OBJECT)
            }
            for (l in target.lists.values) {
                val slot = localSlots.getValue(l.id)
                mv.visitVarInsn(ALOAD, 0)
                emitNewList(mv, slot, l.items)
                mv.visitFieldInsn(PUTFIELD, className, slot.field, RT.D_LIST)
            }
        }

        for (s in scripts) {
            mv.visitVarInsn(ALOAD, 0)
            pushInt(mv, s.hatKind)
            mv.visitLdcInsn(s.hatArg)
            pushInt(mv, s.id)
            mv.visitMethodInsn(INVOKEVIRTUAL, RT.TARGET, "registerHat", "(I${RT.D_STRING}I)V", false)
        }
        mv.visitInsn(RETURN)
        mv.visitMaxs(0, 0)
        mv.visitEnd()
    }

    private fun emitRunScript() {
        val mv = cw.visitMethod(ACC_PUBLIC, "runScript", "(I)V", null, null)
        mv.visitCode()
        if (scripts.isNotEmpty()) {
            val dflt = Label()
            val labels = Array(scripts.size) { Label() }
            mv.visitVarInsn(ILOAD, 1)
            mv.visitTableSwitchInsn(0, scripts.size - 1, dflt, *labels)
            for ((i, s) in scripts.withIndex()) {
                mv.visitLabel(labels[i])
                mv.visitVarInsn(ALOAD, 0)
                mv.visitMethodInsn(INVOKEVIRTUAL, className, s.method, "()V", false)
                mv.visitInsn(RETURN)
            }
            mv.visitLabel(dflt)
        }
        mv.visitInsn(RETURN)
        mv.visitMaxs(0, 0)
        mv.visitEnd()
    }

    private fun emitNewInstance() {
        val mv = cw.visitMethod(ACC_PUBLIC, "newInstance", "()${RT.D_TARGET}", null, null)
        mv.visitCode()
        mv.visitTypeInsn(NEW, className)
        mv.visitInsn(DUP)
        mv.visitVarInsn(ALOAD, 0)
        mv.visitFieldInsn(GETFIELD, RT.TARGET, "rt", RT.D_RUNTIME)
        mv.visitMethodInsn(INVOKESPECIAL, className, "<init>", "(${RT.D_RUNTIME})V", false)
        mv.visitInsn(ARETURN)
        mv.visitMaxs(0, 0)
        mv.visitEnd()
    }

    private fun emitCopyLocalsFrom() {
        val mv = cw.visitMethod(ACC_PUBLIC, "copyLocalsFrom", "(${RT.D_TARGET})V", null, null)
        mv.visitCode()
        if (localSlotList.isNotEmpty()) {
            mv.visitVarInsn(ALOAD, 1)
            mv.visitTypeInsn(CHECKCAST, className)
            mv.visitVarInsn(ASTORE, 2)
            for (s in localSlotList) {
                mv.visitVarInsn(ALOAD, 0)
                mv.visitVarInsn(ALOAD, 2)
                if (s.isList) {
                    mv.visitFieldInsn(GETFIELD, className, s.field, RT.D_LIST)
                    mv.visitMethodInsn(INVOKEVIRTUAL, RT.LIST, "copy", "()${RT.D_LIST}", false)
                    mv.visitFieldInsn(PUTFIELD, className, s.field, RT.D_LIST)
                } else {
                    mv.visitFieldInsn(GETFIELD, className, s.field, RT.D_OBJECT)
                    mv.visitFieldInsn(PUTFIELD, className, s.field, RT.D_OBJECT)
                }
            }
        }
        mv.visitInsn(RETURN)
        mv.visitMaxs(0, 0)
        mv.visitEnd()
    }

    private fun emitLookupVariable() {
        val mv = cw.visitMethod(ACC_PUBLIC, "lookupVariable", "(${RT.D_STRING})${RT.D_OBJECT}", null, null)
        mv.visitCode()
        val slots = (if (target.isStage) ctx.stageSlots else localSlotList).filter { !it.isList }
        for (s in slots) {
            val next = Label()
            mv.visitVarInsn(ALOAD, 1)
            mv.visitLdcInsn(s.name)
            mv.visitMethodInsn(INVOKEVIRTUAL, RT.STRING, "equals", "(${RT.D_OBJECT})Z", false)
            mv.visitJumpInsn(IFEQ, next)
            emitGetSlot(mv, s)
            mv.visitInsn(ARETURN)
            mv.visitLabel(next)
        }
        mv.visitInsn(ACONST_NULL)
        mv.visitInsn(ARETURN)
        mv.visitMaxs(0, 0)
        mv.visitEnd()
    }

    private fun emitScript(s: ScriptInfo) {
        val mv = cw.visitMethod(ACC_PRIVATE, s.method, "()V", null, null)
        mv.visitCode()
        val st = MethodState(mv, null, 1)
        emitStack(st, s.hat.next)
        mv.visitInsn(RETURN)
        mv.visitMaxs(0, 0)
        mv.visitEnd()
    }

    private fun emitProcedure(p: ProcInfo) {
        val desc = "(" + RT.D_OBJECT.repeat(p.argumentIds.size) + ")V"
        val mv = cw.visitMethod(ACC_PRIVATE, p.method, desc, null, null)
        mv.visitCode()
        val st = MethodState(mv, p, 1 + p.argumentIds.size)
        for ((i, name) in p.argumentNames.withIndex()) {
            if (i < p.argumentIds.size) st.argSlots[name] = 1 + i
        }
        if (p.warp) {
            mv.visitVarInsn(ALOAD, 0)
            mv.visitMethodInsn(INVOKEVIRTUAL, RT.TARGET, "enterWarp", "()V", false)
        }
        emitStack(st, p.definition.next)
        emitReturn(st)
        mv.visitMaxs(0, 0)
        mv.visitEnd()
    }

    private fun emitReturn(st: MethodState) {
        if (st.proc?.warp == true) {
            st.mv.visitVarInsn(ALOAD, 0)
            st.mv.visitMethodInsn(INVOKEVIRTUAL, RT.TARGET, "exitWarp", "()V", false)
        }
        st.mv.visitInsn(RETURN)
    }

    // ---- statements -----------------------------------------------------------------------------

    /** Emits a chain of blocks linked through `next`. */
    private fun emitStack(st: MethodState, firstId: String?) {
        var id = firstId
        val seen = HashSet<String>()
        while (id != null && seen.add(id)) {
            val b = target.blocks[id] ?: break
            emitStatement(st, b)
            id = b.next
        }
    }

    private fun emitSubstack(st: MethodState, b: Block, name: String) {
        val ref = b.inputs[name] as? Input.BlockRef ?: return
        emitStack(st, ref.id)
    }

    private fun thisCall(st: MethodState, name: String, desc: String, args: () -> Unit) {
        st.mv.visitVarInsn(ALOAD, 0)
        args()
        st.mv.visitMethodInsn(INVOKEVIRTUAL, RT.TARGET, name, desc, false)
    }

    private fun emitStatement(st: MethodState, b: Block) {
        val mv = st.mv
        when (b.opcode) {
            // motion
            "motion_movesteps" -> {
                thisCall(st, "moveSteps", "(D)V") { emitExpr(st, b.inputs["STEPS"], VType.NUM) }
            }
            "motion_turnright" -> {
                thisCall(st, "turnRight", "(D)V") { emitExpr(st, b.inputs["DEGREES"], VType.NUM) }
            }
            "motion_turnleft" -> {
                thisCall(st, "turnLeft", "(D)V") { emitExpr(st, b.inputs["DEGREES"], VType.NUM) }
            }
            "motion_goto" -> {
                thisCall(st, "goTo", "(${RT.D_OBJECT})V") { emitExpr(st, b.inputs["TO"], VType.ANY) }
            }
            "motion_gotoxy" -> {
                thisCall(st, "goToXY", "(DD)V") {
                    emitExpr(st, b.inputs["X"], VType.NUM)
                    emitExpr(st, b.inputs["Y"], VType.NUM)
                }
            }
            "motion_glideto" -> {
                thisCall(st, "glideTo", "(D${RT.D_OBJECT})V") {
                    emitExpr(st, b.inputs["SECS"], VType.NUM)
                    emitExpr(st, b.inputs["TO"], VType.ANY)
                }
            }
            "motion_glidesecstoxy" -> {
                thisCall(st, "glideToXY", "(DDD)V") {
                    emitExpr(st, b.inputs["SECS"], VType.NUM)
                    emitExpr(st, b.inputs["X"], VType.NUM)
                    emitExpr(st, b.inputs["Y"], VType.NUM)
                }
            }
            "motion_pointindirection" -> {
                thisCall(st, "pointInDirection", "(D)V") { emitExpr(st, b.inputs["DIRECTION"], VType.NUM) }
            }
            "motion_pointtowards" -> {
                thisCall(st, "pointTowards", "(${RT.D_OBJECT})V") { emitExpr(st, b.inputs["TOWARDS"], VType.ANY) }
            }
            "motion_changexby" -> {
                thisCall(st, "changeX", "(D)V") { emitExpr(st, b.inputs["DX"], VType.NUM) }
            }
            "motion_setx" -> {
                thisCall(st, "setX", "(D)V") { emitExpr(st, b.inputs["X"], VType.NUM) }
            }
            "motion_changeyby" -> {
                thisCall(st, "changeY", "(D)V") { emitExpr(st, b.inputs["DY"], VType.NUM) }
            }
            "motion_sety" -> {
                thisCall(st, "setY", "(D)V") { emitExpr(st, b.inputs["Y"], VType.NUM) }
            }
            "motion_ifonedgebounce" -> {
                thisCall(st, "ifOnEdgeBounce", "()V") {}
            }
            "motion_setrotationstyle" -> {
                thisCall(st, "setRotationStyle", "(${RT.D_STRING})V") { mv.visitLdcInsn(field(b, "STYLE")) }
            }

            // looks
            "looks_say" -> {
                thisCall(st, "say", "(${RT.D_OBJECT})V") { emitExpr(st, b.inputs["MESSAGE"], VType.ANY) }
            }
            "looks_think" -> {
                thisCall(st, "think", "(${RT.D_OBJECT})V") { emitExpr(st, b.inputs["MESSAGE"], VType.ANY) }
            }
            "looks_sayforsecs" -> {
                thisCall(st, "sayForSecs", "(${RT.D_OBJECT}D)V") {
                    emitExpr(st, b.inputs["MESSAGE"], VType.ANY)
                    emitExpr(st, b.inputs["SECS"], VType.NUM)
                }
            }
            "looks_thinkforsecs" -> {
                thisCall(st, "thinkForSecs", "(${RT.D_OBJECT}D)V") {
                    emitExpr(st, b.inputs["MESSAGE"], VType.ANY)
                    emitExpr(st, b.inputs["SECS"], VType.NUM)
                }
            }
            "looks_show" -> {
                thisCall(st, "show", "()V") {}
            }
            "looks_hide" -> {
                thisCall(st, "hide", "()V") {}
            }
            "looks_switchcostumeto" -> {
                thisCall(st, "switchCostume", "(${RT.D_OBJECT})V") { emitExpr(st, b.inputs["COSTUME"], VType.ANY) }
            }
            "looks_nextcostume" -> {
                thisCall(st, "nextCostume", "()V") {}
            }
            "looks_switchbackdropto" -> {
                thisCall(st, "switchBackdrop", "(${RT.D_OBJECT})V") { emitExpr(st, b.inputs["BACKDROP"], VType.ANY) }
            }
            "looks_switchbackdroptoandwait" -> {
                thisCall(
                    st,
                    "switchBackdropAndWait",
                    "(${RT.D_OBJECT})V",
                ) { emitExpr(st, b.inputs["BACKDROP"], VType.ANY) }
            }
            "looks_nextbackdrop" -> {
                thisCall(st, "nextBackdrop", "()V") {}
            }
            "looks_changesizeby" -> {
                thisCall(st, "changeSize", "(D)V") { emitExpr(st, b.inputs["CHANGE"], VType.NUM) }
            }
            "looks_setsizeto" -> {
                thisCall(st, "setSize", "(D)V") { emitExpr(st, b.inputs["SIZE"], VType.NUM) }
            }
            "looks_gotofrontback" -> {
                thisCall(st, "goToLayer", "(${RT.D_STRING})V") { mv.visitLdcInsn(field(b, "FRONT_BACK")) }
            }
            "looks_goforwardbackwardlayers" -> {
                thisCall(st, "moveLayers", "(${RT.D_STRING}D)V") {
                    mv.visitLdcInsn(field(b, "FORWARD_BACKWARD"))
                    emitExpr(st, b.inputs["NUM"], VType.NUM)
                }
            }
            "looks_seteffectto" -> {
                thisCall(st, "setEffect", "(${RT.D_STRING}D)V") {
                    mv.visitLdcInsn(field(b, "EFFECT"))
                    emitExpr(st, b.inputs["VALUE"], VType.NUM)
                }
            }
            "looks_changeeffectby" -> {
                thisCall(st, "changeEffect", "(${RT.D_STRING}D)V") {
                    mv.visitLdcInsn(field(b, "EFFECT"))
                    emitExpr(st, b.inputs["CHANGE"], VType.NUM)
                }
            }
            "looks_cleargraphiceffects" -> {
                thisCall(st, "clearEffects", "()V") {}
            }

            // sound
            "sound_play" -> {
                thisCall(st, "playSound", "(${RT.D_OBJECT})V") { emitExpr(st, b.inputs["SOUND_MENU"], VType.ANY) }
            }
            "sound_playuntildone" -> {
                thisCall(
                    st,
                    "playSoundUntilDone",
                    "(${RT.D_OBJECT})V",
                ) { emitExpr(st, b.inputs["SOUND_MENU"], VType.ANY) }
            }
            "sound_stopallsounds" -> {
                thisCall(st, "stopAllSounds", "()V") {}
            }
            "sound_setvolumeto" -> {
                thisCall(st, "setVolume", "(D)V") { emitExpr(st, b.inputs["VOLUME"], VType.NUM) }
            }
            "sound_changevolumeby" -> {
                thisCall(st, "changeVolume", "(D)V") { emitExpr(st, b.inputs["VOLUME"], VType.NUM) }
            }
            "sound_seteffectto", "sound_changeeffectby", "sound_cleareffects" -> {}

            // events
            "event_broadcast" -> {
                thisCall(st, "broadcast", "(${RT.D_OBJECT})V") { emitExpr(st, b.inputs["BROADCAST_INPUT"], VType.ANY) }
            }
            "event_broadcastandwait" -> {
                thisCall(
                    st,
                    "broadcastAndWait",
                    "(${RT.D_OBJECT})V",
                ) { emitExpr(st, b.inputs["BROADCAST_INPUT"], VType.ANY) }
            }

            // control
            "control_wait" -> {
                thisCall(st, "wait", "(D)V") { emitExpr(st, b.inputs["DURATION"], VType.NUM) }
            }
            "control_repeat" -> emitRepeat(st, b)
            "control_forever" -> {
                val loop = Label()
                mv.visitLabel(loop)
                emitSubstack(st, b, "SUBSTACK")
                thisCall(st, "yieldLoop", "()V") {}
                mv.visitJumpInsn(GOTO, loop)
            }
            "control_if" -> {
                val end = Label()
                emitExpr(st, b.inputs["CONDITION"], VType.BOOL)
                mv.visitJumpInsn(IFEQ, end)
                emitSubstack(st, b, "SUBSTACK")
                mv.visitLabel(end)
            }
            "control_if_else" -> {
                val elseL = Label()
                val end = Label()
                emitExpr(st, b.inputs["CONDITION"], VType.BOOL)
                mv.visitJumpInsn(IFEQ, elseL)
                emitSubstack(st, b, "SUBSTACK")
                mv.visitJumpInsn(GOTO, end)
                mv.visitLabel(elseL)
                emitSubstack(st, b, "SUBSTACK2")
                mv.visitLabel(end)
            }
            "control_wait_until" -> {
                val loop = Label()
                val end = Label()
                mv.visitLabel(loop)
                emitExpr(st, b.inputs["CONDITION"], VType.BOOL)
                mv.visitJumpInsn(IFNE, end)
                thisCall(st, "yieldLoop", "()V") {}
                mv.visitJumpInsn(GOTO, loop)
                mv.visitLabel(end)
            }
            "control_repeat_until", "control_while" -> {
                val loop = Label()
                val end = Label()
                mv.visitLabel(loop)
                emitExpr(st, b.inputs["CONDITION"], VType.BOOL)
                mv.visitJumpInsn(if (b.opcode == "control_while") IFEQ else IFNE, end)
                emitSubstack(st, b, "SUBSTACK")
                thisCall(st, "yieldLoop", "()V") {}
                mv.visitJumpInsn(GOTO, loop)
                mv.visitLabel(end)
            }
            "control_stop" -> {
                when (field(b, "STOP_OPTION")) {
                    "all" -> thisCall(st, "stopAll", "()V") {}
                    "this script" -> emitReturn(st)
                    else -> thisCall(st, "stopOtherScripts", "()V") {}
                }
            }
            "control_create_clone_of" -> {
                thisCall(st, "createCloneOf", "(${RT.D_OBJECT})V") { emitExpr(st, b.inputs["CLONE_OPTION"], VType.ANY) }
            }
            "control_delete_this_clone" -> {
                thisCall(st, "deleteThisClone", "()V") {}
            }

            // data
            "data_setvariableto" -> {
                val slot = variableSlot(b, false) ?: return
                emitSetSlot(st, slot) { emitExpr(st, b.inputs["VALUE"], VType.ANY) }
            }
            "data_changevariableby" -> {
                val slot = variableSlot(b, false) ?: return
                emitSetSlot(st, slot) {
                    emitGetSlot(mv, slot)
                    emitExpr(st, b.inputs["VALUE"], VType.NUM)
                    mv.visitMethodInsn(INVOKESTATIC, RT.OPS, "add", "(${RT.D_OBJECT}D)${RT.D_OBJECT}", false)
                }
            }
            "data_showvariable", "data_hidevariable", "data_showlist", "data_hidelist" -> {}
            "data_addtolist" -> {
                listCall(st, b, "add", "(${RT.D_OBJECT})V") { emitExpr(st, b.inputs["ITEM"], VType.ANY) }
            }
            "data_deleteoflist" -> {
                listCall(st, b, "delete", "(${RT.D_OBJECT})V") { emitExpr(st, b.inputs["INDEX"], VType.ANY) }
            }
            "data_deletealloflist" -> {
                listCall(st, b, "deleteAll", "()V") {}
            }
            "data_insertatlist" -> {
                listCall(st, b, "insert", "(${RT.D_OBJECT}${RT.D_OBJECT})V") {
                    emitExpr(st, b.inputs["INDEX"], VType.ANY)
                    emitExpr(st, b.inputs["ITEM"], VType.ANY)
                }
            }
            "data_replaceitemoflist" -> {
                listCall(st, b, "replace", "(${RT.D_OBJECT}${RT.D_OBJECT})V") {
                    emitExpr(st, b.inputs["INDEX"], VType.ANY)
                    emitExpr(st, b.inputs["ITEM"], VType.ANY)
                }
            }

            // sensing
            "sensing_askandwait" -> {
                thisCall(st, "askAndWait", "(${RT.D_OBJECT})V") { emitExpr(st, b.inputs["QUESTION"], VType.ANY) }
            }
            "sensing_resettimer" -> {
                thisCall(st, "resetTimer", "()V") {}
            }
            "sensing_setdragmode" -> {}

            // pen
            "pen_clear" -> {
                thisCall(st, "penClear", "()V") {}
            }
            "pen_stamp" -> {
                thisCall(st, "stamp", "()V") {}
            }
            "pen_penDown" -> {
                thisCall(st, "penDown", "()V") {}
            }
            "pen_penUp" -> {
                thisCall(st, "penUp", "()V") {}
            }
            "pen_setPenColorToColor" -> {
                thisCall(st, "setPenColor", "(${RT.D_OBJECT})V") { emitExpr(st, b.inputs["COLOR"], VType.ANY) }
            }
            "pen_setPenColorParamTo" -> {
                thisCall(st, "setPenParam", "(${RT.D_OBJECT}D)V") {
                    emitExpr(st, b.inputs["COLOR_PARAM"], VType.ANY)
                    emitExpr(st, b.inputs["VALUE"], VType.NUM)
                }
            }
            "pen_changePenColorParamBy" -> {
                thisCall(st, "changePenParam", "(${RT.D_OBJECT}D)V") {
                    emitExpr(st, b.inputs["COLOR_PARAM"], VType.ANY)
                    emitExpr(st, b.inputs["VALUE"], VType.NUM)
                }
            }
            "pen_setPenSizeTo" -> {
                thisCall(st, "setPenSize", "(D)V") { emitExpr(st, b.inputs["SIZE"], VType.NUM) }
            }
            "pen_changePenSizeBy" -> {
                thisCall(st, "changePenSize", "(D)V") { emitExpr(st, b.inputs["SIZE"], VType.NUM) }
            }
            "pen_setPenHueToNumber" -> {
                thisCall(st, "setPenParam", "(${RT.D_OBJECT}D)V") {
                    mv.visitLdcInsn("color")
                    emitExpr(st, b.inputs["HUE"], VType.NUM)
                    pushDouble(mv, 0.5)
                    mv.visitInsn(DMUL)
                }
            }
            "pen_changePenHueBy" -> {
                thisCall(st, "changePenParam", "(${RT.D_OBJECT}D)V") {
                    mv.visitLdcInsn("color")
                    emitExpr(st, b.inputs["HUE"], VType.NUM)
                    pushDouble(mv, 0.5)
                    mv.visitInsn(DMUL)
                }
            }

            // procedures
            "procedures_call" -> emitProcedureCall(st, b)
            "procedures_definition", "procedures_prototype" -> {}
            else -> {
                ctx.warn("Unsupported block '${b.opcode}' in '${target.name}' compiles to a no-op")
                thisCall(st, "unsupported", "(${RT.D_STRING})V") { mv.visitLdcInsn(b.opcode) }
            }
        }
    }

    private fun emitRepeat(st: MethodState, b: Block) {
        val mv = st.mv
        val counter = st.allocLocal(1)
        emitExpr(st, b.inputs["TIMES"], VType.NUM)
        mv.visitMethodInsn(INVOKESTATIC, RT.OPS, "repeatCount", "(D)I", false)
        mv.visitVarInsn(ISTORE, counter)
        val loop = Label()
        val end = Label()
        mv.visitLabel(loop)
        mv.visitVarInsn(ILOAD, counter)
        mv.visitJumpInsn(IFLE, end)
        emitSubstack(st, b, "SUBSTACK")
        mv.visitIincInsn(counter, -1)
        thisCall(st, "yieldLoop", "()V") {}
        mv.visitJumpInsn(GOTO, loop)
        mv.visitLabel(end)
    }

    private fun emitProcedureCall(st: MethodState, b: Block) {
        val mv = st.mv
        val m = b.mutation
        val proc = m?.let { procs[it.proccode] }
        if (m == null || proc == null) {
            ctx.warn("Call to undefined custom block '${m?.proccode ?: "?"}' in '${target.name}'")
            return
        }
        mv.visitVarInsn(ALOAD, 0)
        for ((i, argId) in proc.argumentIds.withIndex()) {
            val input = b.inputs[argId]
            if (input == null || input is Input.Empty) {
                val default = proc.argumentDefaults.getOrNull(i) ?: ""
                if (default == "false") {
                    mv.visitFieldInsn(GETSTATIC, "java/lang/Boolean", "FALSE", "Ljava/lang/Boolean;")
                } else {
                    mv.visitLdcInsn(default)
                }
            } else {
                emitExpr(st, input, VType.ANY)
            }
        }

        val desc = "(" + RT.D_OBJECT.repeat(proc.argumentIds.size) + ")V"
        mv.visitMethodInsn(INVOKEVIRTUAL, className, proc.method, desc, false)
    }

    private fun listCall(st: MethodState, b: Block, name: String, desc: String, args: () -> Unit) {
        val slot = variableSlot(b, true) ?: return
        emitGetSlot(st.mv, slot)
        args()
        st.mv.visitMethodInsn(INVOKEVIRTUAL, RT.LIST, name, desc, false)
    }

    // ---- variables ------------------------------------------------------------------------------

    private fun variableSlot(b: Block, isList: Boolean): VarSlot? {
        val f = b.fields[if (isList) "LIST" else "VARIABLE"]
        if (f == null) {
            ctx.warn("Block '${b.opcode}' without a variable field in '${target.name}'")
            return null
        }
        return resolveSlot(f.id, f.value, isList)
    }

    private fun resolveSlot(id: String?, name: String, isList: Boolean): VarSlot {
        if (id != null) {
            localSlots[id]?.let { if (it.isList == isList) return it }
            ctx.globalById(id)?.let { if (it.isList == isList) return it }
        }
        localSlotsByName[if (isList) "list:$name" else name]?.let { return it }
        ctx.globalByName(name, isList)?.let { return it }
        // scratch-vm creates missing variables on the fly as sprite-local; do the same.
        ctx.warn("Variable '$name' is referenced but not declared in '${target.name}'; creating it")
        val slot = VarSlot(className, fieldNames.unique(if (isList) "l_" else "v_", name), false, isList, name)
        if (id != null) localSlots[id] = slot
        localSlotsByName[if (isList) "list:$name" else name] = slot
        localSlotList.add(slot)
        cw.visitField(ACC_PUBLIC, slot.field, if (isList) RT.D_LIST else RT.D_OBJECT, null, null).visitEnd()
        if (target.isStage) {
            ctx.warn("Undeclared variable '$name' on the stage is not initialized")
        }
        return slot
    }

    private fun emitGetSlot(mv: MethodVisitor, slot: VarSlot) {
        val desc = if (slot.isList) RT.D_LIST else RT.D_OBJECT
        if (slot.isStatic) {
            mv.visitFieldInsn(GETSTATIC, slot.owner, slot.field, desc)
        } else {
            mv.visitVarInsn(ALOAD, 0)
            mv.visitFieldInsn(GETFIELD, slot.owner, slot.field, desc)
            if (slot.isList) {
                // Undeclared lists created on the fly start out null; lazily allocate.
                val ok = Label()
                mv.visitInsn(DUP)
                mv.visitJumpInsn(IFNONNULL, ok)
                mv.visitInsn(POP)
                mv.visitVarInsn(ALOAD, 0)
                mv.visitTypeInsn(NEW, RT.LIST)
                mv.visitInsn(DUP)
                mv.visitMethodInsn(INVOKESPECIAL, RT.LIST, "<init>", "()V", false)
                mv.visitInsn(DUP_X1)
                mv.visitFieldInsn(PUTFIELD, slot.owner, slot.field, desc)
                mv.visitLabel(ok)
            }
        }
    }

    private fun emitSetSlot(st: MethodState, slot: VarSlot, value: () -> Unit) {
        val mv = st.mv
        if (slot.isStatic) {
            value()
            mv.visitFieldInsn(PUTSTATIC, slot.owner, slot.field, RT.D_OBJECT)
        } else {
            mv.visitVarInsn(ALOAD, 0)
            value()
            mv.visitFieldInsn(PUTFIELD, slot.owner, slot.field, RT.D_OBJECT)
        }
    }

    // ---- expressions ----------------------------------------------------------------------------

    /** Emits [input] so that a value of type [want] ends up on the stack. */
    private fun emitExpr(st: MethodState, input: Input?, want: VType) {
        val mv = st.mv
        when (input) {
            null, is Input.Empty -> pushDefault(mv, want)
            is Input.Literal -> emitLiteral(mv, input, want)
            is Input.BroadcastRef -> {
                mv.visitLdcInsn(input.name)
                coerce(mv, VType.STR, want)
            }
            is Input.VariableRef -> {
                emitGetSlot(mv, resolveSlot(input.id, input.name, false))
                coerce(mv, VType.ANY, want)
            }
            is Input.ListRef -> {
                emitGetSlot(mv, resolveSlot(input.id, input.name, true))
                mv.visitMethodInsn(INVOKEVIRTUAL, RT.LIST, "contents", "()${RT.D_STRING}", false)
                coerce(mv, VType.STR, want)
            }
            is Input.BlockRef -> {
                val b = target.blocks[input.id]
                if (b == null) {
                    pushDefault(mv, want)
                    return
                }
                if (isMenu(b)) {
                    mv.visitLdcInsn(
                        b.fields.values
                            .first()
                            .value,
                    )
                    coerce(mv, VType.STR, want)
                    return
                }

                val produced = emitBlockExpr(st, b, want)
                coerce(mv, produced, want)
            }
        }
    }

    /** Shadow menu blocks (`looks_costume`, `motion_goto_menu`, ...) carry their value in their single field. */
    private fun isMenu(b: Block): Boolean = b.shadow && b.inputs.isEmpty() && b.fields.size == 1

    private fun emitLiteral(mv: MethodVisitor, lit: Input.Literal, want: VType) {
        val numeric = lit.type in PrimitiveType.NUMBER..PrimitiveType.ANGLE
        val n = parseNumber(lit.value)
        when (want) {
            VType.NUM -> pushDouble(mv, if (n.isNaN()) 0.0 else n)
            VType.STR -> {
                mv.visitLdcInsn(
                    if (numeric && !n.isNaN() &&
                        lit.value.isNotBlank()
                    ) {
                        formatNumber(n)
                    } else {
                        lit.value
                    },
                )
            }
            VType.BOOL -> {
                val b = !(lit.value.isEmpty() || lit.value == "0" || lit.value.equals("false", ignoreCase = true))
                mv.visitInsn(if (b) ICONST_1 else ICONST_0)
            }
            VType.ANY -> {
                if (numeric && !n.isNaN() && lit.value.isNotBlank()) {
                    pushDouble(mv, n)
                    coerce(mv, VType.NUM, VType.ANY)
                } else {
                    mv.visitLdcInsn(lit.value)
                }
            }
        }
    }

    /** Emits a reporter block and returns the type it left on the stack. */
    private fun emitBlockExpr(st: MethodState, b: Block, want: VType): VType {
        val mv = st.mv

        fun num(name: String) = emitExpr(st, b.inputs[name], VType.NUM)

        fun any(name: String) = emitExpr(st, b.inputs[name], VType.ANY)

        fun bool(name: String) = emitExpr(st, b.inputs[name], VType.BOOL)

        fun call(name: String, desc: String, ret: VType, args: () -> Unit): VType {
            mv.visitVarInsn(ALOAD, 0)
            args()
            mv.visitMethodInsn(INVOKEVIRTUAL, RT.TARGET, name, desc, false)
            return ret
        }

        fun arith(op: Int): VType {
            num("NUM1")
            num("NUM2")
            mv.visitInsn(op)
            return VType.NUM
        }

        return when (b.opcode) {
            // operators
            "operator_add" -> arith(DADD)
            "operator_subtract" -> arith(DSUB)
            "operator_multiply" -> arith(DMUL)
            "operator_divide" -> arith(DDIV)
            "operator_mod" -> {
                num("NUM1")
                num("NUM2")
                mv.visitMethodInsn(INVOKESTATIC, RT.OPS, "mod", "(DD)D", false)
                VType.NUM
            }
            "operator_round" -> {
                num("NUM")
                mv.visitMethodInsn(INVOKESTATIC, RT.OPS, "round", "(D)D", false)
                VType.NUM
            }
            "operator_mathop" -> {
                mv.visitLdcInsn(field(b, "OPERATOR"))
                num("NUM")
                mv.visitMethodInsn(INVOKESTATIC, RT.OPS, "mathop", "(${RT.D_STRING}D)D", false)
                VType.NUM
            }
            "operator_random" -> {
                any("FROM")
                any("TO")
                mv.visitMethodInsn(INVOKESTATIC, RT.OPS, "random", "(${RT.D_OBJECT}${RT.D_OBJECT})D", false)
                VType.NUM
            }
            "operator_lt" -> emitCompare(st, b, IFLT)
            "operator_gt" -> emitCompare(st, b, IFGT)
            "operator_equals" -> emitCompare(st, b, IFEQ)
            "operator_and" -> {
                bool("OPERAND1")
                bool("OPERAND2")
                mv.visitInsn(IAND)
                VType.BOOL
            }
            "operator_or" -> {
                bool("OPERAND1")
                bool("OPERAND2")
                mv.visitInsn(IOR)
                VType.BOOL
            }
            "operator_not" -> {
                bool("OPERAND")
                mv.visitInsn(ICONST_1)
                mv.visitInsn(IXOR)
                VType.BOOL
            }
            "operator_join" -> {
                any("STRING1")
                any("STRING2")
                mv.visitMethodInsn(INVOKESTATIC, RT.OPS, "join", "(${RT.D_OBJECT}${RT.D_OBJECT})${RT.D_STRING}", false)
                VType.STR
            }
            "operator_letter_of" -> {
                any("LETTER")
                any("STRING")
                mv.visitMethodInsn(
                    INVOKESTATIC,
                    RT.OPS,
                    "letterOf",
                    "(${RT.D_OBJECT}${RT.D_OBJECT})${RT.D_STRING}",
                    false,
                )
                VType.STR
            }
            "operator_length" -> {
                any("STRING")
                mv.visitMethodInsn(INVOKESTATIC, RT.OPS, "length", "(${RT.D_OBJECT})D", false)
                VType.NUM
            }
            "operator_contains" -> {
                any("STRING1")
                any("STRING2")
                mv.visitMethodInsn(INVOKESTATIC, RT.OPS, "contains", "(${RT.D_OBJECT}${RT.D_OBJECT})Z", false)
                VType.BOOL
            }

            // data
            "data_variable" -> {
                val slot = variableSlot(b, false)
                if (slot == null) {
                    pushDefault(mv, want)
                    want
                } else {
                    emitGetSlot(mv, slot)
                    VType.ANY
                }
            }
            "data_listcontents" -> {
                val slot = variableSlot(b, true)
                if (slot == null) {
                    pushDefault(mv, want)
                    want
                } else {
                    emitGetSlot(mv, slot)
                    mv.visitMethodInsn(INVOKEVIRTUAL, RT.LIST, "contents", "()${RT.D_STRING}", false)
                    VType.STR
                }
            }
            "data_itemoflist" -> {
                listExpr(st, b, want, "item", "(${RT.D_OBJECT})${RT.D_OBJECT}", VType.ANY) { any("INDEX") }
            }
            "data_itemnumoflist" -> {
                listExpr(st, b, want, "indexOf", "(${RT.D_OBJECT})D", VType.NUM) { any("ITEM") }
            }
            "data_lengthoflist" -> {
                listExpr(st, b, want, "length", "()D", VType.NUM) {}
            }
            "data_listcontainsitem" -> {
                listExpr(st, b, want, "contains", "(${RT.D_OBJECT})Z", VType.BOOL) { any("ITEM") }
            }

            // arguments
            "argument_reporter_string_number", "argument_reporter_boolean" -> {
                val name = field(b, "VALUE")
                val slot = st.argSlots[name]
                if (slot != null) {
                    mv.visitVarInsn(ALOAD, slot)
                    VType.ANY
                } else {
                    if (b.opcode == "argument_reporter_boolean") mv.visitInsn(ICONST_0) else pushDouble(mv, 0.0)
                    if (b.opcode == "argument_reporter_boolean") VType.BOOL else VType.NUM
                }
            }

            // motion
            "motion_xposition" -> {
                call("xPosition", "()D", VType.NUM) {}
            }
            "motion_yposition" -> {
                call("yPosition", "()D", VType.NUM) {}
            }
            "motion_direction" -> {
                call("getDirection", "()D", VType.NUM) {}
            }

            // looks
            "looks_costumenumbername" -> {
                if (field(b, "NUMBER_NAME") == "number") {
                    call("costumeNumber", "()D", VType.NUM) {}
                } else {
                    call("costumeName", "()${RT.D_STRING}", VType.STR) {}
                }
            }
            "looks_backdropnumbername" -> {
                if (field(b, "NUMBER_NAME") == "number") {
                    call("backdropNumber", "()D", VType.NUM) {}
                } else {
                    call("backdropName", "()${RT.D_STRING}", VType.STR) {}
                }
            }
            "looks_size" -> {
                call("getSize", "()D", VType.NUM) {}
            }

            // sound
            "sound_volume" -> {
                call("getVolume", "()D", VType.NUM) {}
            }

            // sensing
            "sensing_touchingobject" -> {
                call("touching", "(${RT.D_OBJECT})Z", VType.BOOL) { any("TOUCHINGOBJECTMENU") }
            }
            "sensing_touchingcolor" -> {
                call("touchingColor", "(${RT.D_OBJECT})Z", VType.BOOL) { any("COLOR") }
            }
            "sensing_coloristouchingcolor" -> {
                ctx.warn("Block 'sensing_coloristouchingcolor' is not supported yet; it reports false")
                mv.visitInsn(ICONST_0)
                VType.BOOL
            }
            "sensing_distanceto" -> {
                call("distanceTo", "(${RT.D_OBJECT})D", VType.NUM) { any("DISTANCETOMENU") }
            }
            "sensing_answer" -> {
                call("answer", "()${RT.D_STRING}", VType.STR) {}
            }
            "sensing_keypressed" -> {
                call("keyPressed", "(${RT.D_OBJECT})Z", VType.BOOL) { any("KEY_OPTION") }
            }
            "sensing_mousedown" -> {
                call("mouseDown", "()Z", VType.BOOL) {}
            }
            "sensing_mousex" -> {
                call("mouseX", "()D", VType.NUM) {}
            }
            "sensing_mousey" -> {
                call("mouseY", "()D", VType.NUM) {}
            }
            "sensing_timer" -> {
                call("timer", "()D", VType.NUM) {}
            }
            "sensing_of" -> {
                call("sensingOf", "(${RT.D_STRING}${RT.D_OBJECT})${RT.D_OBJECT}", VType.ANY) {
                    mv.visitLdcInsn(field(b, "PROPERTY"))
                    any("OBJECT")
                }
            }
            "sensing_current" -> {
                call("current", "(${RT.D_STRING})D", VType.NUM) { mv.visitLdcInsn(field(b, "CURRENTMENU")) }
            }
            "sensing_dayssince2000" -> {
                call("daysSince2000", "()D", VType.NUM) {}
            }
            "sensing_username" -> {
                mv.visitLdcInsn("")
                VType.STR
            }
            "sensing_loudness" -> {
                pushDouble(mv, -1.0)
                VType.NUM
            }
            else -> {
                ctx.warn("Unsupported reporter '${b.opcode}' in '${target.name}' reports an empty value")
                mv.visitVarInsn(ALOAD, 0)
                mv.visitLdcInsn(b.opcode)
                mv.visitMethodInsn(INVOKEVIRTUAL, RT.TARGET, "unsupported", "(${RT.D_STRING})V", false)
                pushDefault(mv, want)
                want
            }
        }
    }

    private fun listExpr(
        st: MethodState,
        b: Block,
        want: VType,
        name: String,
        desc: String,
        ret: VType,
        args: () -> Unit,
    ): VType {
        val slot = variableSlot(b, true)
        if (slot == null) {
            pushDefault(st.mv, want)
            return want
        }
        emitGetSlot(st.mv, slot)
        args()
        st.mv.visitMethodInsn(INVOKEVIRTUAL, RT.LIST, name, desc, false)
        return ret
    }

    /** `<`, `>` and `=`: numeric compare when both sides are statically numbers, else Scratch's `Cast.compare`. */
    private fun emitCompare(st: MethodState, b: Block, jump: Int): VType {
        val mv = st.mv
        val a = b.inputs["OPERAND1"]
        val c = b.inputs["OPERAND2"]
        if (naturalType(a) == VType.NUM && naturalType(c) == VType.NUM) {
            emitExpr(st, a, VType.NUM)
            emitExpr(st, c, VType.NUM)
            // NaN must make every comparison false: dcmpg yields 1 for NaN, dcmpl yields -1.
            mv.visitInsn(if (jump == IFLT) DCMPG else DCMPL)
        } else {
            emitExpr(st, a, VType.ANY)
            emitExpr(st, c, VType.ANY)
            mv.visitMethodInsn(INVOKESTATIC, RT.CAST, "compare", "(${RT.D_OBJECT}${RT.D_OBJECT})D", false)
            mv.visitInsn(DCONST_0)
            mv.visitInsn(DCMPL)
        }

        val yes = Label()
        val end = Label()
        mv.visitJumpInsn(jump, yes)
        mv.visitInsn(ICONST_0)
        mv.visitJumpInsn(GOTO, end)
        mv.visitLabel(yes)
        mv.visitInsn(ICONST_1)
        mv.visitLabel(end)
        return VType.BOOL
    }

    /** The type [emitExpr] would produce for an input without emitting anything. */
    private fun naturalType(input: Input?): VType = when (input) {
        null, is Input.Empty -> VType.ANY
        is Input.Literal -> {
            val numeric = input.type in PrimitiveType.NUMBER..PrimitiveType.ANGLE
            if (numeric && !parseNumber(input.value).isNaN() && input.value.isNotBlank()) VType.NUM else VType.STR
        }
        is Input.BroadcastRef, is Input.ListRef -> VType.STR
        is Input.VariableRef -> VType.ANY
        is Input.BlockRef -> {
            val b = target.blocks[input.id] ?: return VType.ANY
            when (b.opcode) {
                "operator_add", "operator_subtract", "operator_multiply", "operator_divide", "operator_mod",
                "operator_round", "operator_mathop", "operator_random", "operator_length",
                "motion_xposition", "motion_yposition", "motion_direction", "looks_size", "sound_volume",
                "sensing_mousex", "sensing_mousey", "sensing_timer", "sensing_dayssince2000", "sensing_current",
                "sensing_distanceto", "sensing_loudness", "data_lengthoflist", "data_itemnumoflist",
                -> VType.NUM
                "operator_join", "operator_letter_of", "sensing_answer", "sensing_username", "data_listcontents",
                -> VType.STR
                else -> VType.ANY
            }
        }
    }

    // ---- small helpers --------------------------------------------------------------------------

    private fun field(b: Block, name: String): String = b.fields[name]?.value ?: ""

    private fun coerce(mv: MethodVisitor, from: VType, to: VType) {
        if (from == to) return
        when (from) {
            VType.NUM -> {
                when (to) {
                    VType.ANY -> {
                        mv.visitMethodInsn(INVOKESTATIC, "java/lang/Double", "valueOf", "(D)Ljava/lang/Double;", false)
                    }
                    VType.STR -> {
                        mv.visitMethodInsn(INVOKESTATIC, RT.CAST, "toString", "(D)${RT.D_STRING}", false)
                    }
                    VType.BOOL -> mv.visitMethodInsn(INVOKESTATIC, RT.CAST, "toBoolean", "(D)Z", false)
                    VType.NUM -> {}
                }
            }
            VType.STR -> {
                when (to) {
                    VType.ANY -> {}
                    VType.NUM -> {
                        mv.visitMethodInsn(INVOKESTATIC, RT.CAST, "toNumber", "(${RT.D_STRING})D", false)
                    }
                    VType.BOOL -> {
                        mv.visitMethodInsn(INVOKESTATIC, RT.CAST, "toBoolean", "(${RT.D_STRING})Z", false)
                    }
                    VType.STR -> {}
                }
            }
            VType.BOOL -> {
                when (to) {
                    VType.ANY -> {
                        mv.visitMethodInsn(
                            INVOKESTATIC,
                            "java/lang/Boolean",
                            "valueOf",
                            "(Z)Ljava/lang/Boolean;",
                            false,
                        )
                    }
                    VType.NUM -> mv.visitInsn(I2D)
                    VType.STR -> {
                        mv.visitMethodInsn(INVOKESTATIC, RT.CAST, "toString", "(Z)${RT.D_STRING}", false)
                    }
                    VType.BOOL -> {}
                }
            }
            VType.ANY -> {
                when (to) {
                    VType.NUM -> {
                        mv.visitMethodInsn(INVOKESTATIC, RT.CAST, "toNumber", "(${RT.D_OBJECT})D", false)
                    }
                    VType.STR -> {
                        mv.visitMethodInsn(INVOKESTATIC, RT.CAST, "toString", "(${RT.D_OBJECT})${RT.D_STRING}", false)
                    }
                    VType.BOOL -> {
                        mv.visitMethodInsn(INVOKESTATIC, RT.CAST, "toBoolean", "(${RT.D_OBJECT})Z", false)
                    }
                    VType.ANY -> {}
                }
            }
        }
    }

    private fun pushDefault(mv: MethodVisitor, t: VType) {
        when (t) {
            VType.NUM -> mv.visitInsn(DCONST_0)
            VType.STR, VType.ANY -> mv.visitLdcInsn("")
            VType.BOOL -> mv.visitInsn(ICONST_0)
        }
    }

    /** Pushes a boxed Scratch constant (Double, String or Boolean). */
    private fun pushConstant(mv: MethodVisitor, value: Any) {
        when (value) {
            is Double -> {
                pushDouble(mv, value)
                coerce(mv, VType.NUM, VType.ANY)
            }
            is Boolean -> {
                mv.visitFieldInsn(GETSTATIC, "java/lang/Boolean", if (value) "TRUE" else "FALSE", "Ljava/lang/Boolean;")
            }
            else -> {
                mv.visitLdcInsn(value.toString())
            }
        }
    }

    companion object {
        const val INLINE_LIST_LIMIT = 32

        private val decimal = Regex("""^[+-]?(\d+\.?\d*|\.\d+)([eE][+-]?\d+)?$""")

        /** Mirrors `Cast.toNumberOrNaN` for strings so literals can be folded at compile time. */
        fun parseNumber(raw: String): Double {
            val s = raw.trim()
            if (s.isEmpty()) return 0.0
            return when {
                decimal.matches(s) -> s.toDouble()
                s == "Infinity" || s == "+Infinity" -> Double.POSITIVE_INFINITY
                s == "-Infinity" -> Double.NEGATIVE_INFINITY
                s.length > 2 && (s.startsWith("0x") || s.startsWith("0X")) -> {
                    s.substring(2).toLongOrNull(16)?.toDouble()
                        ?: Double.NaN
                }
                else -> {
                    Double.NaN
                }
            }
        }

        /** Mirrors `Cast.toString(double)` for the common cases literals hit. */
        fun formatNumber(n: Double): String {
            if (n == Math.rint(n) && Math.abs(n) < 1e15) return n.toLong().toString()
            return n.toString()
        }

        fun pushInt(mv: MethodVisitor, v: Int) {
            when {
                v in -1..5 -> mv.visitInsn(ICONST_0 + v)
                v in Byte.MIN_VALUE..Byte.MAX_VALUE -> mv.visitIntInsn(BIPUSH, v)
                v in Short.MIN_VALUE..Short.MAX_VALUE -> mv.visitIntInsn(SIPUSH, v)
                else -> mv.visitLdcInsn(v)
            }
        }

        fun pushDouble(mv: MethodVisitor, v: Double) {
            when {
                v == 0.0 && 1.0 / v > 0 -> mv.visitInsn(DCONST_0)
                v == 1.0 -> mv.visitInsn(DCONST_1)
                else -> mv.visitLdcInsn(v)
            }
        }
    }
}
