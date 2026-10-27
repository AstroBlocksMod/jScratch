package org.jscratch.compiler.codegen

import org.jscratch.compiler.CompileOptions
import org.jscratch.compiler.sb3.Project
import org.jscratch.compiler.sb3.Target
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes

/** Output of code generation: class files by internal name, extra resources, and warnings. */
class GeneratedProject(
    val classes: LinkedHashMap<String, ByteArray>,
    val resources: LinkedHashMap<String, ByteArray>,
    val mainClass: String,
    val warnings: List<String>,
)

/**
 * A variable or list and where it lives in generated code. [index] numbers the slots of one target, variables
 * first and then lists, and [monitor] is the slot's entry in the monitor table, or -1 when it has none.
 */
class VarSlot(
    val owner: String,
    val field: String,
    val isStatic: Boolean,
    val isList: Boolean,
    val name: String,
    val index: Int,
) {
    var monitor: Int = -1
}

/** Everything target compilers share: the options, class names and the global variable table. */
class ProjectContext(val project: Project, val options: CompileOptions) {
    val warnings = ArrayList<String>()
    val classNames = HashMap<Target, String>()
    private val stageVars = HashMap<String, VarSlot>()
    private val stageVarsByName = HashMap<String, VarSlot>()
    val stageSlots = ArrayList<VarSlot>()
    val resources = LinkedHashMap<String, ByteArray>()
    val monitors: ArrayList<MonitorDef> = ArrayList(Monitors.build(project) { warn(it) })
    private val monitorsByVar = HashMap<Pair<Target, String>, Int>()

    init {
        for ((i, m) in monitors.withIndex()) {
            if (m.varId != null) monitorsByVar[m.owner to m.varId] = i
        }
        val names = Names()
        for (t in project.targets) {
            classNames[t] = if (t.isStage) RT.STAGE_CLASS else "${RT.PACKAGE}/${names.unique("Sprite_", t.name)}"
        }
        val fieldNames = Names()
        for (v in project.stage.variables.values) {
            val slot = VarSlot(RT.STAGE_CLASS, fieldNames.unique("v_", v.name), true, false, v.name, stageSlots.size)
            slot.monitor = monitorIndex(project.stage, v.id)
            stageVars[v.id] = slot
            stageVarsByName[v.name] = slot
            stageSlots.add(slot)
        }
        for (l in project.stage.lists.values) {
            val slot = VarSlot(RT.STAGE_CLASS, fieldNames.unique("l_", l.name), true, true, l.name, stageSlots.size)
            slot.monitor = monitorIndex(project.stage, l.id)
            stageVars[l.id] = slot
            stageVarsByName["list:" + l.name] = slot
            stageSlots.add(slot)
        }
    }

    fun globalById(id: String): VarSlot? = stageVars[id]

    /** The monitor of [target]'s variable or list [id], or -1. */
    fun monitorIndex(target: Target, id: String): Int = monitorsByVar[target to id] ?: -1

    /** Adds a monitor for a variable the code generator created on the fly, and returns its index. */
    fun addMonitor(def: MonitorDef): Int {
        monitors.add(def)
        return monitors.size - 1
    }

    fun globalByName(name: String, isList: Boolean): VarSlot? = stageVarsByName[if (isList) "list:$name" else name]

    fun warn(message: String) {
        if (!warnings.contains(message)) warnings.add(message)
    }

    /** Stores a large list's initial contents as a resource instead of inline constants. */
    fun addListResource(className: String, field: String, items: List<Any>): String {
        val path = "data/${className.substringAfterLast('/')}/$field.txt"
        resources[path] = ListData.encode(items).toByteArray(Charsets.UTF_8)
        return path
    }
}

/**
 * ClassWriter that never loads classes to compute stack map frames. Generated
 * code keeps the operand stack empty at every join point, so `Object` is always
 * a correct common supertype.
 */
class SafeClassWriter : ClassWriter(COMPUTE_FRAMES or COMPUTE_MAXS) {
    override fun getCommonSuperClass(type1: String, type2: String): String = RT.OBJECT
}

object ProjectCompiler {
    fun compile(project: Project, options: CompileOptions): GeneratedProject {
        val ctx = ProjectContext(project, options)
        val classes = LinkedHashMap<String, ByteArray>()
        for (t in project.targets) {
            val name = ctx.classNames.getValue(t)
            classes[name] = TargetCompiler(ctx, t, name).compile()
        }
        classes[RT.MAIN] = mainClass(ctx)
        // Written last, since compiling the targets can add monitors for variables created on the fly.
        if (ctx.monitors.isNotEmpty()) {
            ctx.resources[Monitors.RESOURCE] = Monitors.encode(ctx.monitors).toByteArray(Charsets.UTF_8)
        }
        return GeneratedProject(classes, ctx.resources, RT.MAIN, ctx.warnings)
    }

    private fun mainClass(ctx: ProjectContext): ByteArray {
        val cw = SafeClassWriter()
        cw.visit(
            Opcodes.V21,
            Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL or Opcodes.ACC_SUPER,
            RT.MAIN,
            null,
            RT.OBJECT,
            null,
        )
        cw.visitSource("project.json", null)

        val init = cw.visitMethod(Opcodes.ACC_PRIVATE, "<init>", "()V", null, null)
        init.visitCode()
        init.visitVarInsn(Opcodes.ALOAD, 0)
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, RT.OBJECT, "<init>", "()V", false)
        init.visitInsn(Opcodes.RETURN)
        init.visitMaxs(0, 0)
        init.visitEnd()

        val mv = cw.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "main", "([Ljava/lang/String;)V", null, null)
        mv.visitCode()
        mv.visitTypeInsn(Opcodes.NEW, RT.RUNTIME)
        mv.visitInsn(Opcodes.DUP)
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, RT.RUNTIME, "<init>", "()V", false)
        mv.visitVarInsn(Opcodes.ASTORE, 1)
        val ordered = listOf(ctx.project.stage) + ctx.project.sprites.sortedBy { it.layerOrder }
        for (t in ordered) {
            val cls = ctx.classNames.getValue(t)
            mv.visitVarInsn(Opcodes.ALOAD, 1)
            mv.visitTypeInsn(Opcodes.NEW, cls)
            mv.visitInsn(Opcodes.DUP)
            mv.visitVarInsn(Opcodes.ALOAD, 1)
            mv.visitMethodInsn(Opcodes.INVOKESPECIAL, cls, "<init>", "(${RT.D_RUNTIME})V", false)
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, RT.RUNTIME, "addTarget", "(${RT.D_TARGET})V", false)
        }
        mv.visitVarInsn(Opcodes.ALOAD, 1)
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, RT.RUNTIME, "run", "()V", false)
        mv.visitInsn(Opcodes.RETURN)
        mv.visitMaxs(0, 0)
        mv.visitEnd()
        cw.visitEnd()
        return cw.toByteArray()
    }
}
