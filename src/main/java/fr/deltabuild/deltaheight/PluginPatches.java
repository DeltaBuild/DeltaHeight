package fr.deltabuild.deltaheight;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

final class PluginPatches implements Opcodes {
	static final int MIN_Y = -64;
	static final int MAX_Y = 320;

	private static final Map<String, DeltaHeightTransformer.Patch> TARGETS = new HashMap<String, DeltaHeightTransformer.Patch>();

	static {
		//WorldEdit
		TARGETS.put("com/sk89q/worldedit/EditSession", new WeEditSession());
		TARGETS.put("com/sk89q/worldedit/regions/CuboidRegion", new WeCuboidRegion());
		TARGETS.put("com/sk89q/worldedit/regions/Polygonal2DRegion", new WeMathMaxZero());
		TARGETS.put("com/sk89q/worldedit/LocalPlayer", new WeLocalPlayer());
		TARGETS.put("com/sk89q/worldedit/commands/SelectionCommands", new WeChunkSelection());
		//VoxelSniper
		TARGETS.put("com/thevoxelbox/voxelsniper/RangeBlockHelper", new VsRangeBlockHelper());
		TARGETS.put("com/thevoxelbox/voxelsniper/brush/CylinderBrush", new VsClampFromGetY());
		TARGETS.put("com/thevoxelbox/voxelsniper/brush/CloneStampBrush", new VsClampFromGetY());
	}

	private PluginPatches() {
	}

	static boolean handles(String internalName) {
		return TARGETS.containsKey(internalName);
	}

	static byte[] transform(String internalName, byte[] bytes) {
		DeltaHeightTransformer.Patch p = TARGETS.get(internalName);
		if (p == null || bytes == null) {
			return null;
		}
		try {
			ClassNode cn = Asm.read(bytes);
			int n = p.apply(cn);
			if (n <= 0) {
				DeltaHeightAgent.log("plugin " + internalName.replace('/', '.') + " : motif absent (version différente ?) -> classe inchangée");
				return null;
			}
			DeltaHeightAgent.log("plugin " + internalName.replace('/', '.') + " : " + n + " modification(s)");
			return Asm.write(cn);
		} catch (Throwable t) {
			DeltaHeightAgent.log("plugin " + internalName + " : patch ignoré (" + t + ")");
			return null;
		}
	}

	static void floorCompare(MethodNode m, AbstractInsnNode load, AbstractInsnNode jump) {
		((JumpInsnNode) jump).setOpcode(jump.getOpcode() == IFLT ? IF_ICMPLT : IF_ICMPGE);
		m.instructions.insert(load, new IntInsnNode(BIPUSH, MIN_Y));
	}

	static boolean isFloorJump(AbstractInsnNode n) {
		return n != null && (n.getOpcode() == IFLT || n.getOpcode() == IFGE);
	}

	//EditSession : rawSetBlock et makeCylinder
	static final class WeEditSession implements DeltaHeightTransformer.Patch {
		public int apply(ClassNode cn) {
			int n = 0;
			for (MethodNode m : cn.methods) {
				if ("rawSetBlock".equals(m.name) && m.desc.startsWith("(Lcom/sk89q/worldedit/Vector;Lcom/sk89q/worldedit/blocks/BaseBlock;)")) {
					int ySlot = -1;
					for (AbstractInsnNode in = m.instructions.getFirst(); in != null; in = in.getNext()) {
						if (Asm.isCall(in, null, "getBlockY", "()I") && Asm.next(in) != null && Asm.next(in).getOpcode() == ISTORE) {
							ySlot = Asm.var(Asm.next(in));
							break;
						}
					}
					for (AbstractInsnNode in = m.instructions.getFirst(); in != null && ySlot >= 0; in = in.getNext()) {
						if (Asm.isIload(in, ySlot) && isFloorJump(Asm.next(in))) {
							floorCompare(m, in, Asm.next(in));
							++n;
						}
					}
				} else {
					for (AbstractInsnNode in = m.instructions.getFirst(); in != null; in = in.getNext()) {
						if (Asm.isCall(in, "com/sk89q/worldedit/Vector", "getBlockY", "()I") && isFloorJump(Asm.next(in))) {
							AbstractInsnNode j = Asm.next(in);
							AbstractInsnNode a = Asm.next(j);
							AbstractInsnNode z = Asm.next(a);
							AbstractInsnNode set = Asm.next(z);
							if (j.getOpcode() == IFGE && a != null && a.getOpcode() == ALOAD && Asm.isInt(z, 0) && Asm.isCall(set, "com/sk89q/worldedit/Vector", "setY", "(I)Lcom/sk89q/worldedit/Vector;")) {
								floorCompare(m, in, j);
								m.instructions.set(z, new IntInsnNode(BIPUSH, MIN_Y));
								n += 2;
							}
						}
					}
				}
			}
			return n;
		}
	}

	//CuboidRegion : Y min à -64
	static final class WeCuboidRegion implements DeltaHeightTransformer.Patch {
		public int apply(ClassNode cn) {
			int n = 0;
			MethodNode m = Asm.method(cn, "recalculate", "()V");
			if (m == null) {
				return 0;
			}
			for (AbstractInsnNode in = m.instructions.getFirst(); in != null; in = in.getNext()) {
				if (Asm.isInt(in, 0) && Asm.prev(in) instanceof FieldInsnNode && "Lcom/sk89q/worldedit/Vector;".equals(((FieldInsnNode) Asm.prev(in)).desc)) {
					m.instructions.set(in, in = new IntInsnNode(BIPUSH, MIN_Y));
					++n;
				}
			}
			return n;
		}
	}

	//Polygonal2DRegion : Y min à -64
	static final class WeMathMaxZero implements DeltaHeightTransformer.Patch {
		public int apply(ClassNode cn) {
			int n = 0;
			for (MethodNode m : cn.methods) {
				for (AbstractInsnNode in = m.instructions.getFirst(); in != null; in = in.getNext()) {
					if (Asm.isInt(in, 0)) {
						AbstractInsnNode a = Asm.next(in);
						AbstractInsnNode f = Asm.next(a);
						AbstractInsnNode c = Asm.next(f);
						if (a != null && a.getOpcode() == ALOAD && f instanceof FieldInsnNode && f.getOpcode() == GETFIELD
								&& ("minY".equals(((FieldInsnNode) f).name) || "maxY".equals(((FieldInsnNode) f).name))
								&& Asm.isCall(c, "java/lang/Math", "max", "(II)I")) {
							m.instructions.set(in, in = new IntInsnNode(BIPUSH, MIN_Y));
							++n;
						}
					}
				}
			}
			return n;
		}
	}

	//LocalPlayer : setOnGround / descendLevel
	static final class WeLocalPlayer implements DeltaHeightTransformer.Patch {
		public int apply(ClassNode cn) {
			int n = 0;
			for (MethodNode m : cn.methods) {
				Set<Integer> ySlots = new HashSet<Integer>();
				for (AbstractInsnNode in = m.instructions.getFirst(); in != null; in = in.getNext()) {
					if (Asm.isCall(in, "java/lang/Math", "max", "(II)I") && Asm.next(in) != null && Asm.next(in).getOpcode() == ISTORE) {
						AbstractInsnNode p = in;
						boolean fromY = false;
						AbstractInsnNode zero = null;
						for (int k = 0; k < 8 && p != null; ++k) {
							p = Asm.prev(p);
							if (Asm.isCall(p, null, "getBlockY", "()I")) {
								fromY = true;
							}
							if (Asm.isInt(p, 0)) {
								zero = p;
								break;
							}
						}
						if (fromY && zero != null) {
							m.instructions.set(zero, new IntInsnNode(BIPUSH, MIN_Y));
							ySlots.add(Asm.var(Asm.next(in)));
							++n;
						}
					}
				}
				for (AbstractInsnNode in = m.instructions.getFirst(); in != null; in = in.getNext()) {
					if (!Asm.isIload(in) || !ySlots.contains(Asm.var(in))) {
						continue;
					}
					AbstractInsnNode nx = Asm.next(in);
					if (isFloorJump(nx)) {
						floorCompare(m, in, nx);
						++n;
					} else if (Asm.isInt(nx, 1) && Asm.next(nx) != null && (Asm.next(nx).getOpcode() == IF_ICMPGE || Asm.next(nx).getOpcode() == IF_ICMPLT)) {
						m.instructions.set(nx, new IntInsnNode(BIPUSH, MIN_Y + 1));
						++n;
					}
				}
			}
			return n;
		}
	}

	//Commande //chunk
	static final class WeChunkSelection implements DeltaHeightTransformer.Patch {
		public int apply(ClassNode cn) {
			int n = 0;
			MethodNode m = Asm.method(cn, "chunk", null);
			if (m == null) {
				return 0;
			}
			for (AbstractInsnNode in = m.instructions.getFirst(); in != null; in = in.getNext()) {
				if (Asm.isInt(in, 0) && Asm.prev(in) != null && Asm.prev(in).getOpcode() == IMUL && Asm.isInt(Asm.prev(Asm.prev(in)), 16)) {
					m.instructions.set(in, in = new IntInsnNode(BIPUSH, MIN_Y));
					++n;
				}
			}
			return n;
		}
	}

	//Visée de -64 à 319
	static final class VsRangeBlockHelper implements DeltaHeightTransformer.Patch {
		public int apply(ClassNode cn) {
			int n = 0;
			for (MethodNode m : cn.methods) {
				for (AbstractInsnNode in = m.instructions.getFirst(); in != null; in = in.getNext()) {
					if (!(in instanceof FieldInsnNode) || in.getOpcode() != GETFIELD) {
						continue;
					}
					String f = ((FieldInsnNode) in).name;
					if (!"targetY".equals(f) && !"lastY".equals(f)) {
						continue;
					}
					AbstractInsnNode nx = Asm.next(in);
					if (isFloorJump(nx)) {
						floorCompare(m, in, nx);
						++n;
					} else if (Asm.isInt(nx, 255) && Asm.next(nx) != null && Asm.next(nx).getOpcode() >= IF_ICMPEQ && Asm.next(nx).getOpcode() <= IF_ICMPLE) {
						m.instructions.set(nx, new IntInsnNode(SIPUSH, MAX_Y - 1));
						++n;
					}
				}
			}
			return n;
		}
	}

	//Brosses
	static final class VsClampFromGetY implements DeltaHeightTransformer.Patch {
		public int apply(ClassNode cn) {
			int n = 0;
			for (MethodNode m : cn.methods) {
				Set<Integer> ySlots = new HashSet<Integer>();
				for (AbstractInsnNode in = m.instructions.getFirst(); in != null; in = in.getNext()) {
					if (in.getOpcode() == ISTORE) {
						AbstractInsnNode p = in;
						for (int k = 0; k < 9 && p != null; ++k) {
							p = Asm.prev(p);
							if (p != null && p.getOpcode() == ISTORE) {
								break;
							}
							if (p instanceof MethodInsnNode && ("getY".equals(((MethodInsnNode) p).name) || "getBlockPositionY".equals(((MethodInsnNode) p).name)) && "()I".equals(((MethodInsnNode) p).desc)) {
								ySlots.add(Asm.var(in));
								break;
							}
						}
					}
				}
				for (AbstractInsnNode in = m.instructions.getFirst(); in != null; in = in.getNext()) {
					if (Asm.isIload(in) && ySlots.contains(Asm.var(in)) && isFloorJump(Asm.next(in))) {
						AbstractInsnNode j = Asm.next(in);
						floorCompare(m, in, j);
						++n;
						AbstractInsnNode a = Asm.next(j);
						AbstractInsnNode b = Asm.next(a);
						if (j.getOpcode() == IF_ICMPGE && Asm.isInt(a, 0) && b != null && b.getOpcode() == ISTORE && Asm.var(b) == Asm.var(in)) {
							m.instructions.set(a, new IntInsnNode(BIPUSH, MIN_Y));
							++n;
						}
					}
				}
			}
			return n;
		}
	}
}
