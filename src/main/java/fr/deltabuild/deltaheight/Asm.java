package fr.deltabuild.deltaheight;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

final class Asm implements Opcodes {
	private Asm() {
	}

	static ClassNode read(byte[] bytes) {
		ClassNode cn = new ClassNode();
		new ClassReader(bytes).accept(cn, ClassReader.EXPAND_FRAMES);
		return cn;
	}

	static byte[] write(ClassNode cn) {
		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		cn.accept(cw);
		return cw.toByteArray();
	}

	static boolean isMeta(AbstractInsnNode n) {
		return n instanceof LabelNode || n instanceof LineNumberNode || n instanceof FrameNode;
	}

	static AbstractInsnNode next(AbstractInsnNode n) {
		if (n == null) {
			return null;
		}
		n = n.getNext();
		while (n != null && isMeta(n)) {
			n = n.getNext();
		}
		return n;
	}

	static AbstractInsnNode prev(AbstractInsnNode n) {
		if (n == null) {
			return null;
		}
		n = n.getPrevious();
		while (n != null && isMeta(n)) {
			n = n.getPrevious();
		}
		return n;
	}

	static MethodNode method(ClassNode cn, String name, String desc) {
		for (MethodNode m : cn.methods) {
			if (m.name.equals(name) && (desc == null || m.desc.equals(desc))) {
				return m;
			}
		}
		return null;
	}

	static MethodNode require(ClassNode cn, String name, String desc) {
		MethodNode m = method(cn, name, desc);
		if (m == null) {
			throw new IllegalStateException("méthode introuvable " + cn.name + "." + name + (desc == null ? "" : desc));
		}
		return m;
	}

	static boolean isIload(AbstractInsnNode n, int var) {
		return n != null && n.getOpcode() == ILOAD && ((VarInsnNode) n).var == var;
	}

	static boolean isIload(AbstractInsnNode n) {
		return n != null && n.getOpcode() == ILOAD;
	}

	static int var(AbstractInsnNode n) {
		return ((VarInsnNode) n).var;
	}

	static boolean isInt(AbstractInsnNode n, int v) {
		if (n == null) {
			return false;
		}
		int op = n.getOpcode();
		if (op >= ICONST_M1 && op <= ICONST_5) {
			return op - ICONST_0 == v;
		}
		if (op == BIPUSH || op == SIPUSH) {
			return ((IntInsnNode) n).operand == v;
		}
		if (op == LDC) {
			Object c = ((LdcInsnNode) n).cst;
			return c instanceof Integer && ((Integer) c).intValue() == v;
		}
		return false;
	}

	static AbstractInsnNode pushInt(int v) {
		if (v >= -1 && v <= 5) {
			return new org.objectweb.asm.tree.InsnNode(ICONST_0 + v);
		}
		if (v >= Byte.MIN_VALUE && v <= Byte.MAX_VALUE) {
			return new IntInsnNode(BIPUSH, v);
		}
		if (v >= Short.MIN_VALUE && v <= Short.MAX_VALUE) {
			return new IntInsnNode(SIPUSH, v);
		}
		return new LdcInsnNode(Integer.valueOf(v));
	}

	static boolean isCall(AbstractInsnNode n, String owner, String name, String desc) {
		if (!(n instanceof MethodInsnNode)) {
			return false;
		}
		MethodInsnNode m = (MethodInsnNode) n;
		return (owner == null || owner.equals(m.owner)) && (name == null || name.equals(m.name)) && (desc == null || desc.equals(m.desc));
	}

	static boolean isField(AbstractInsnNode n, int op, String owner, String name) {
		if (!(n instanceof FieldInsnNode) || n.getOpcode() != op) {
			return false;
		}
		FieldInsnNode f = (FieldInsnNode) n;
		return (owner == null || owner.equals(f.owner)) && (name == null || name.equals(f.name));
	}

	static void check(boolean ok, String what) {
		if (!ok) {
			throw new IllegalStateException("motif introuvable : " + what);
		}
	}
}
