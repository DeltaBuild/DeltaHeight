package fr.deltabuild.deltaheight;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

final class Patches implements Opcodes {
	static final int MIN_Y = -64;
	static final int MAX_Y = 320;
	static final int SECTIONS = 24;

	static final String CHUNK = "net/minecraft/world/chunk/Chunk";
	static final String EBS = "net/minecraft/world/chunk/storage/ExtendedBlockStorage";

	private Patches() {
	}

	//Limites Y : 0..255 -> -64..319
	static final class BoundsPatch implements Patch0 {
		private final boolean clamp255;

		BoundsPatch(boolean clamp255) {
			this.clamp255 = clamp255;
		}

		public int apply(ClassNode cn) {
			int total = 0;
			for (MethodNode m : cn.methods) {
				total += this.patchMethod(m);
			}
			return total;
		}

		private int patchMethod(MethodNode m) {
			int n = 0;
			Set<Integer> ySlots = new HashSet<Integer>();
			//Bornes hautes
			for (AbstractInsnNode in = m.instructions.getFirst(); in != null; in = in.getNext()) {
				if (in.getOpcode() != SIPUSH) {
					continue;
				}
				IntInsnNode c = (IntInsnNode) in;
				AbstractInsnNode p = Asm.prev(in);
				AbstractInsnNode nx = Asm.next(in);
				boolean cmp = nx != null && nx.getOpcode() >= IF_ICMPEQ && nx.getOpcode() <= IF_ICMPLE;
				if (Asm.isIload(p) && cmp && (c.operand == 256 || c.operand == 255)) {
					c.operand = c.operand == 256 ? MAX_Y : MAX_Y - 1;
					ySlots.add(Asm.var(p));
					++n;
				}
			}
			if (ySlots.isEmpty()) {
				return n;
			}
			//Bornes basses
			for (AbstractInsnNode in = m.instructions.getFirst(); in != null; in = in.getNext()) {
				if (this.clamp255 && in.getOpcode() == SIPUSH && ((IntInsnNode) in).operand == 255) {
					AbstractInsnNode nx = Asm.next(in);
					if (nx != null && nx.getOpcode() == ISTORE && ySlots.contains(Asm.var(nx))) {
						((IntInsnNode) in).operand = MAX_Y - 1;
						++n;
					}
					continue;
				}
				if (in.getOpcode() == ILOAD && ySlots.contains(Asm.var(in))) {
					AbstractInsnNode nx = Asm.next(in);
					if (nx != null && (nx.getOpcode() == IFLT || nx.getOpcode() == IFGE)) {
						((JumpInsnNode) nx).setOpcode(nx.getOpcode() == IFLT ? IF_ICMPLT : IF_ICMPGE);
						m.instructions.insert(in, new IntInsnNode(BIPUSH, MIN_Y));
						++n;
						AbstractInsnNode a = Asm.next(nx);
						AbstractInsnNode b = Asm.next(a);
						if (nx.getOpcode() == IF_ICMPGE && a != null && a.getOpcode() == ICONST_0 && b != null && b.getOpcode() == ISTORE && Asm.var(b) == Asm.var(in)) {
							m.instructions.set(a, new IntInsnNode(BIPUSH, MIN_Y));
							++n;
						}
					}
				}
			}
			if ("(IIIIII)Z".equals(m.desc)) {
				AbstractInsnNode first = m.instructions.getFirst();
				while (first != null && Asm.isMeta(first)) {
					first = first.getNext();
				}
				AbstractInsnNode nx = Asm.next(first);
				if (Asm.isIload(first) && !ySlots.contains(Asm.var(first)) && nx != null && (nx.getOpcode() == IFLT || nx.getOpcode() == IFGE)) {
					((JumpInsnNode) nx).setOpcode(nx.getOpcode() == IFLT ? IF_ICMPLT : IF_ICMPGE);
					m.instructions.insert(first, new IntInsnNode(BIPUSH, MIN_Y));
					++n;
				}
			}
			return n;
		}
	}

	//Collisions sous Y 0 (borne ystart de MCPC)
	static final class WorldPatch implements Patch0 {
		public int apply(ClassNode cn) {
			int n = new BoundsPatch(true).apply(cn);
			int c = 0;
			for (MethodNode m : cn.methods) {
				if (!"func_72945_a".equals(m.name)) {
					continue;
				}
				for (AbstractInsnNode in = m.instructions.getFirst(); in != null; in = in.getNext()) {
					if (in.getOpcode() != IFGE) {
						continue;
					}
					AbstractInsnNode sub = Asm.prev(in);
					AbstractInsnNode one = Asm.prev(sub);
					AbstractInsnNode load = Asm.prev(one);
					AbstractInsnNode zero = Asm.next(in);
					AbstractInsnNode go = Asm.next(zero);
					if (sub != null && sub.getOpcode() == ISUB && Asm.isInt(one, 1) && Asm.isIload(load)
							&& Asm.isInt(zero, 0) && go != null && go.getOpcode() == GOTO) {
						((JumpInsnNode) in).setOpcode(IF_ICMPGE);
						m.instructions.insert(sub, new IntInsnNode(BIPUSH, MIN_Y));
						m.instructions.set(zero, new IntInsnNode(BIPUSH, MIN_Y));
						++c;
					}
				}
			}
			if (c > 0) {
				DeltaHeightAgent.log("collisions actives de Y -64 à 319 (correctif getCollidingBoundingBoxes)");
			}
			if (c == 0) {
				DeltaHeightAgent.log("ATTENTION : borne ystart de getCollidingBoundingBoxes introuvable (collisions sous Y 0 possiblement absentes)");
			}
			return n + c;
		}
	}

	//WorldProvider : hauteur 320
	static final class WorldProviderPatch implements Patch0 {
		public int apply(ClassNode cn) {
			int n = 0;
			for (MethodNode m : cn.methods) {
				if (!"()I".equals(m.desc) || !("getHeight".equals(m.name) || "getActualHeight".equals(m.name))) {
					continue;
				}
				for (AbstractInsnNode in = m.instructions.getFirst(); in != null; in = in.getNext()) {
					if (in.getOpcode() == SIPUSH) {
						IntInsnNode c = (IntInsnNode) in;
						if (c.operand == 256) {
							c.operand = MAX_Y;
							++n;
						} else if (c.operand == 128) {
							c.operand = 192;
							++n;
						}
					}
				}
			}
			return n;
		}
	}

	//AnvilChunkLoader : lecture et sauvegarde en 24 sections
	static final class AnvilChunkLoaderPatch implements Patch0 {
		public int apply(ClassNode cn) {
			int n = 0;
			MethodNode read = Asm.require(cn, "func_75823_a", "(Lnet/minecraft/world/World;Lnet/minecraft/nbt/NBTTagCompound;)Lnet/minecraft/world/chunk/Chunk;");
			//Tableau de 16 -> 24 sections
			int arraySlot = -1;
			for (AbstractInsnNode in = read.instructions.getFirst(); in != null; in = in.getNext()) {
				if (in.getOpcode() == ANEWARRAY && EBS.equals(((TypeInsnNode) in).desc)) {
					AbstractInsnNode size = Asm.prev(in);
					if (Asm.isInt(size, 16)) {
						read.instructions.set(size, Asm.pushInt(SECTIONS));
						++n;
					} else if (Asm.isIload(size)) {
						AbstractInsnNode store = null;
						for (AbstractInsnNode s = read.instructions.getFirst(); s != null && s != in; s = s.getNext()) {
							if (s.getOpcode() == ISTORE && Asm.var(s) == Asm.var(size) && Asm.isInt(Asm.prev(s), 16)) {
								store = s;
							}
						}
						Asm.check(store != null, "taille du tableau de sections");
						read.instructions.set(Asm.prev(store), Asm.pushInt(SECTIONS));
						++n;
					}
					AbstractInsnNode st = Asm.next(in);
					if (st != null && st.getOpcode() == ASTORE) {
						arraySlot = Asm.var(st);
					}
					break;
				}
			}
			Asm.check(n == 1 && arraySlot >= 0, "new ExtendedBlockStorage[16]");
			//Index de section + 4
			int stores = 0;
			for (AbstractInsnNode in = read.instructions.getFirst(); in != null; in = in.getNext()) {
				if (in.getOpcode() != AASTORE) {
					continue;
				}
				AbstractInsnNode val = Asm.prev(in);
				AbstractInsnNode idx = Asm.prev(val);
				AbstractInsnNode arr = Asm.prev(idx);
				if (arr != null && arr.getOpcode() == ALOAD && Asm.var(arr) == arraySlot && Asm.isIload(idx) && val.getOpcode() == ALOAD) {
					InsnList add = new InsnList();
					add.add(new InsnNode(ICONST_4));
					add.add(new InsnNode(IADD));
					read.instructions.insert(idx, add);
					++stores;
				}
			}
			Asm.check(stores == 1, "sections[Y] = section");
			n += stores;
			//Hook chargement
			int rets = 0;
			for (AbstractInsnNode in = read.instructions.getFirst(); in != null; in = in.getNext()) {
				if (in.getOpcode() == ARETURN) {
					InsnList hook = new InsnList();
					hook.add(new InsnNode(DUP));
					hook.add(new VarInsnNode(ALOAD, 2));
					hook.add(new MethodInsnNode(INVOKESTATIC, CHUNK, "dhOnLoad", "(Lnet/minecraft/world/chunk/Chunk;Lnet/minecraft/nbt/NBTTagCompound;)V", false));
					read.instructions.insertBefore(in, hook);
					++rets;
				}
			}
			Asm.check(rets > 0, "areturn de readChunkFromNBT");
			n += rets;
			//Hook sauvegarde
			MethodNode write = Asm.require(cn, "func_75820_a", "(Lnet/minecraft/world/chunk/Chunk;Lnet/minecraft/world/World;Lnet/minecraft/nbt/NBTTagCompound;)V");
			InsnList hook = new InsnList();
			hook.add(new VarInsnNode(ALOAD, 1));
			hook.add(new VarInsnNode(ALOAD, 3));
			hook.add(new MethodInsnNode(INVOKESTATIC, CHUNK, "dhOnSave", "(Lnet/minecraft/world/chunk/Chunk;Lnet/minecraft/nbt/NBTTagCompound;)V", false));
			write.instructions.insert(hook);
			++n;
			return n;
		}
	}

	//Paquets de chunks : masque sur 24 sections

	static int maskWritesToInt(MethodNode m) {
		int n = 0;
		for (AbstractInsnNode in = m.instructions.getFirst(); in != null; in = in.getNext()) {
			if (Asm.isCall(in, "java/io/DataOutput", "writeShort", "(I)V")) {
				AbstractInsnNode i2s = Asm.prev(in);
				AbstractInsnNode iand = Asm.prev(i2s);
				AbstractInsnNode mask = Asm.prev(iand);
				if (i2s != null && i2s.getOpcode() == I2S && iand != null && iand.getOpcode() == IAND && Asm.isInt(mask, 65535)) {
					m.instructions.remove(mask);
					m.instructions.remove(iand);
					m.instructions.remove(i2s);
					((MethodInsnNode) in).name = "writeInt";
					++n;
				}
			}
		}
		return n;
	}

	static int replaceInt(MethodNode m, int from, int to) {
		int n = 0;
		for (AbstractInsnNode in = m.instructions.getFirst(); in != null; in = in.getNext()) {
			if (Asm.isInt(in, from)) {
				m.instructions.set(in, Asm.pushInt(to));
				++n;
				break;
			}
		}
		return n;
	}

	static int replaceAllInt(MethodNode m, int from, int to) {
		int n = 0;
		AbstractInsnNode in = m.instructions.getFirst();
		while (in != null) {
			AbstractInsnNode next = in.getNext();
			if (Asm.isInt(in, from)) {
				m.instructions.set(in, Asm.pushInt(to));
				++n;
			}
			in = next;
		}
		return n;
	}

	static int sectionLoopTo24(MethodNode m) {
		int n = 0;
		for (AbstractInsnNode in = m.instructions.getFirst(); in != null; in = in.getNext()) {
			if (Asm.isIload(in) && Asm.isInt(Asm.next(in), 16)) {
				AbstractInsnNode c = Asm.next(in);
				AbstractInsnNode j = Asm.next(c);
				if (j != null && j.getOpcode() == IF_ICMPGE) {
					m.instructions.set(c, Asm.pushInt(SECTIONS));
					++n;
				}
			}
		}
		return n;
	}

	static final class Packet51Patch implements Patch0 {
		public int apply(ClassNode cn) {
			int n = 0;
			MethodNode clinit = Asm.require(cn, "<clinit>", "()V");
			int c = replaceInt(clinit, 196864, 393216);
			Asm.check(c == 1, "Packet51 buffer 196864");
			n += c;
			MethodNode write = Asm.require(cn, "func_73273_a", "(Ljava/io/DataOutput;)V");
			c = maskWritesToInt(write);
			Asm.check(c == 2, "Packet51 writeShort(mask)");
			n += c;
			MethodNode read = Asm.require(cn, "func_73267_a", "(Ljava/io/DataInput;)V");
			int r = 0;
			for (AbstractInsnNode in = read.instructions.getFirst(); in != null; in = in.getNext()) {
				if (Asm.isCall(in, "java/io/DataInput", "readShort", "()S")) {
					((MethodInsnNode) in).name = "readInt";
					((MethodInsnNode) in).desc = "()I";
					++r;
				}
			}
			Asm.check(r == 2, "Packet51 readShort(mask)");
			n += r + sectionLoopTo24(read);
			MethodNode size = Asm.method(cn, "func_73284_a", "()I");
			if (size != null) {
				n += replaceInt(size, 17, 21);
			}
			return n;
		}
	}

	static final class Packet56Patch implements Patch0 {
		public int apply(ClassNode cn) {
			int n = 0;
			MethodNode ctor = Asm.require(cn, "<init>", "(Ljava/util/List;)V");
			int c = replaceInt(ctor, 65535, 0xFFFFFF);
			Asm.check(c == 1, "Packet56 masque 65535");
			n += c;
			MethodNode write = Asm.require(cn, "func_73273_a", "(Ljava/io/DataOutput;)V");
			c = maskWritesToInt(write);
			Asm.check(c == 2, "Packet56 writeShort(mask)");
			n += c;
			MethodNode read = Asm.require(cn, "func_73267_a", "(Ljava/io/DataInput;)V");
			boolean first = true;
			int r = 0;
			for (AbstractInsnNode in = read.instructions.getFirst(); in != null; in = in.getNext()) {
				if (Asm.isCall(in, "java/io/DataInput", "readShort", "()S")) {
					if (first) {
						first = false; //Nombre de chunks
						continue;
					}
					((MethodInsnNode) in).name = "readInt";
					((MethodInsnNode) in).desc = "()I";
					++r;
				}
			}
			Asm.check(r == 2, "Packet56 readShort(mask)");
			n += r + replaceAllInt(read, 196864, 393216) + sectionLoopTo24(read);
			MethodNode size = Asm.method(cn, "func_73284_a", "()I");
			if (size != null) {
				n += replaceInt(size, 12, 16);
			}
			return n;
		}
	}

	//Packet14/15/53 : Y en short
	static final class BlockYShortPatch implements Patch0 {
		public int apply(ClassNode cn) {
			int n = 0;
			MethodNode read = Asm.require(cn, "func_73267_a", "(Ljava/io/DataInput;)V");
			List<MethodInsnNode> calls = ioCalls(read, "java/io/DataInput");
			for (int i = 1; i < calls.size() - 1; ++i) {
				MethodInsnNode cur = calls.get(i);
				if (is(cur, "readUnsignedByte", "()I") && is(calls.get(i - 1), "readInt", "()I") && is(calls.get(i + 1), "readInt", "()I")) {
					cur.name = "readShort";
					cur.desc = "()S";
					++n;
					break;
				}
			}
			Asm.check(n == 1, cn.name + " lecture Y");
			MethodNode write = Asm.require(cn, "func_73273_a", "(Ljava/io/DataOutput;)V");
			calls = ioCalls(write, "java/io/DataOutput");
			for (int i = 1; i < calls.size() - 1; ++i) {
				MethodInsnNode cur = calls.get(i);
				if (is(cur, "write", "(I)V") && is(calls.get(i - 1), "writeInt", "(I)V") && is(calls.get(i + 1), "writeInt", "(I)V")) {
					cur.name = "writeShort";
					++n;
					break;
				}
			}
			Asm.check(n == 2, cn.name + " écriture Y");
			return n;
		}

		private static List<MethodInsnNode> ioCalls(MethodNode m, String owner) {
			List<MethodInsnNode> out = new java.util.ArrayList<MethodInsnNode>();
			for (AbstractInsnNode in = m.instructions.getFirst(); in != null; in = in.getNext()) {
				if (in instanceof MethodInsnNode && owner.equals(((MethodInsnNode) in).owner)) {
					out.add((MethodInsnNode) in);
				}
			}
			return out;
		}

		private static boolean is(MethodInsnNode m, String name, String desc) {
			return name.equals(m.name) && desc.equals(m.desc);
		}
	}

	//Packet61 : effet supprimé hors 0..255
	static final class Packet61Patch implements Patch0 {
		public int apply(ClassNode cn) {
			MethodNode ctor = Asm.require(cn, "<init>", "(IIIIIZ)V");
			String sfx = null;
			for (AbstractInsnNode in = ctor.instructions.getFirst(); in != null; in = in.getNext()) {
				if (in.getOpcode() == PUTFIELD && Asm.prev(in) != null && Asm.isIload(Asm.prev(in), 1)) {
					sfx = ((FieldInsnNode) in).name;
				}
			}
			Asm.check(sfx != null, "Packet61 champ sfx");
			int n = 0;
			for (AbstractInsnNode in = ctor.instructions.getFirst(); in != null; in = in.getNext()) {
				if (in.getOpcode() == RETURN) {
					InsnList l = new InsnList();
					l.add(new VarInsnNode(ALOAD, 0));
					l.add(new VarInsnNode(ALOAD, 0));
					l.add(new FieldInsnNode(GETFIELD, cn.name, sfx, "I"));
					l.add(new VarInsnNode(ILOAD, 3));
					l.add(new MethodInsnNode(INVOKESTATIC, CHUNK, "dhAuxSfx", "(II)I", false));
					l.add(new FieldInsnNode(PUTFIELD, cn.name, sfx, "I"));
					ctor.instructions.insertBefore(in, l);
					++n;
				}
			}
			return n;
		}
	}

	//Envoi du marqueur NGNEID
	static final class MarkerPatch implements Patch0 {
		public int apply(ClassNode cn) {
			MethodNode m = Asm.require(cn, "func_72355_a", "(Lnet/minecraft/network/INetworkManager;Lnet/minecraft/entity/player/EntityPlayerMP;)V");
			for (AbstractInsnNode in = m.instructions.getFirst(); in != null; in = in.getNext()) {
				if (Asm.isCall(in, "net/minecraft/network/packet/Packet1Login", "<init>", null)) {
					AbstractInsnNode send = Asm.next(in);
					Asm.check(Asm.isCall(send, "net/minecraft/network/NetServerHandler", "func_72567_b", "(Lnet/minecraft/network/packet/Packet;)V"), "envoi Packet1Login");
					InsnList l = new InsnList();
					l.add(new VarInsnNode(ALOAD, 2));
					l.add(new FieldInsnNode(GETFIELD, "net/minecraft/entity/player/EntityPlayerMP", "field_71135_a", "Lnet/minecraft/network/NetServerHandler;"));
					l.add(new MethodInsnNode(INVOKESTATIC, CHUNK, "dhMarkerPacket", "()Lnet/minecraft/network/packet/Packet;", false));
					l.add(new MethodInsnNode(INVOKEVIRTUAL, "net/minecraft/network/NetServerHandler", "func_72567_b", "(Lnet/minecraft/network/packet/Packet;)V", false));
					m.instructions.insert(send, l);
					return 1;
				}
			}
			throw new IllegalStateException("Packet1Login introuvable");
		}
	}

	//PlayerInstance
	static final class PlayerInstancePatch implements Patch0 {
		public int apply(ClassNode cn) {
			String field = "field_73261_d";
			int n = 0;
			for (FieldNode f : cn.fields) {
				if (f.name.equals(field)) {
					Asm.check("[S".equals(f.desc), "type de " + field);
					f.desc = "[I";
					f.signature = null;
					++n;
				}
			}
			Asm.check(n == 1, "champ " + field);
			for (MethodNode m : cn.methods) {
				for (AbstractInsnNode in = m.instructions.getFirst(); in != null; in = in.getNext()) {
					if (in instanceof FieldInsnNode && field.equals(((FieldInsnNode) in).name) && cn.name.equals(((FieldInsnNode) in).owner)) {
						((FieldInsnNode) in).desc = "[I";
					} else if (in.getOpcode() == NEWARRAY && ((IntInsnNode) in).operand == T_SHORT) {
						AbstractInsnNode put = Asm.next(in);
						if (Asm.isField(put, PUTFIELD, cn.name, field)) {
							((IntInsnNode) in).operand = T_INT;
							++n;
						}
					} else if (in.getOpcode() == SALOAD) {
						m.instructions.set(in, in = new InsnNode(IALOAD));
						++n;
					} else if (in.getOpcode() == SASTORE) {
						m.instructions.set(in, in = new InsnNode(IASTORE));
						++n;
					} else if (Asm.isCall(in, "java/util/Arrays", "copyOf", "([SI)[S")) {
						((MethodInsnNode) in).desc = "([II)[I";
						++n;
					} else if (in instanceof FrameNode) {
						fixFrame((FrameNode) in);
					}
				}
			}
			MethodNode flag = Asm.require(cn, "func_73259_a", "(III)V");
			int k = 0;
			for (AbstractInsnNode in = flag.instructions.getFirst(); in != null; in = in.getNext()) {
				//Masque de section + 4
				if (Asm.isIload(in, 2) && Asm.isInt(Asm.next(in), 4) && Asm.next(Asm.next(in)).getOpcode() == ISHR) {
					AbstractInsnNode shr = Asm.next(Asm.next(in));
					AbstractInsnNode after = Asm.next(shr);
					if (after != null && after.getOpcode() == ISHL) {
						InsnList add = new InsnList();
						add.add(new InsnNode(ICONST_4));
						add.add(new InsnNode(IADD));
						flag.instructions.insert(shr, add);
						++k;
					}
				}
			}
			Asm.check(k == 1, "masque 1 << (y >> 4)");
			//Encodage de la position avec Y complet
			int enc = 0;
			for (AbstractInsnNode in = flag.instructions.getFirst(); in != null; in = in.getNext()) {
				if (in.getOpcode() == I2S) {
					AbstractInsnNode start = in;
					int guard = 0;
					while (start != null && guard++ < 12 && !(Asm.isIload(start, 1) && Asm.isInt(Asm.next(start), 12))) {
						start = Asm.prev(start);
					}
					Asm.check(start != null && Asm.isIload(start, 1), "encodage (x << 12 | z << 8 | y)");
					AbstractInsnNode cur = start;
					while (cur != in) {
						AbstractInsnNode nx = cur.getNext();
						flag.instructions.remove(cur);
						cur = nx;
					}
					InsnList l = new InsnList();
					l.add(new VarInsnNode(ILOAD, 1));
					l.add(new VarInsnNode(ILOAD, 2));
					l.add(new VarInsnNode(ILOAD, 3));
					l.add(new MethodInsnNode(INVOKESTATIC, CHUNK, "dhEncodePos", "(III)I", false));
					flag.instructions.insertBefore(in, l);
					flag.instructions.remove(in);
					++enc;
					break;
				}
			}
			Asm.check(enc == 1, "encodage position");
			n += k + enc;
			//Décodage Y et Packet52
			MethodNode flush = Asm.require(cn, "func_73254_a", "()V");
			int dec = 0;
			int p52 = 0;
			AbstractInsnNode in = flush.instructions.getFirst();
			while (in != null) {
				AbstractInsnNode next = in.getNext();
				if (in.getOpcode() == IALOAD && Asm.isInt(Asm.next(in), 255) && Asm.next(Asm.next(in)).getOpcode() == IAND) {
					AbstractInsnNode c = Asm.next(in);
					AbstractInsnNode and = Asm.next(c);
					next = and.getNext();
					flush.instructions.remove(c);
					flush.instructions.set(and, new MethodInsnNode(INVOKESTATIC, CHUNK, "dhDecodeY", "(I)I", false));
					++dec;
				} else if (in.getOpcode() == NEW && "net/minecraft/network/packet/Packet52MultiBlockChange".equals(((TypeInsnNode) in).desc)) {
					AbstractInsnNode dup = Asm.next(in);
					Asm.check(dup.getOpcode() == DUP, "new Packet52; dup");
					AbstractInsnNode init = dup;
					while (init != null && !Asm.isCall(init, "net/minecraft/network/packet/Packet52MultiBlockChange", "<init>", null)) {
						init = init.getNext();
					}
					Asm.check(init != null, "Packet52.<init>");
					AbstractInsnNode send = Asm.next(init);
					Asm.check(Asm.isCall(send, cn.name, "func_73256_a", null), "envoi Packet52");
					next = send.getNext();
					flush.instructions.remove(in);
					flush.instructions.remove(dup);
					flush.instructions.set(init, new MethodInsnNode(INVOKESTATIC, CHUNK, "dhMultiBlock", "(Lnet/minecraft/server/management/PlayerInstance;II[IILnet/minecraft/world/World;)V", false));
					flush.instructions.remove(send);
					++p52;
				}
				in = next;
			}
			Asm.check(dec == 2, "décodage Y (" + dec + ")");
			Asm.check(p52 == 1, "Packet52");
			n += dec + p52;
			return n;
		}

		private static void fixFrame(FrameNode f) {
			fixList(f.local);
			fixList(f.stack);
		}

		private static void fixList(List<Object> l) {
			if (l != null) {
				for (int i = 0; i < l.size(); ++i) {
					if ("[S".equals(l.get(i))) {
						l.set(i, "[I");
					}
				}
			}
		}
	}

	//Divers
	static final class BuildLimitPatch implements Patch0 {
		public int apply(ClassNode cn) {
			int n = 0;
			for (MethodNode m : cn.methods) {
				for (AbstractInsnNode in = m.instructions.getFirst(); in != null; in = in.getNext()) {
					if (Asm.isCall(in, "net/minecraft/util/MathHelper", "func_76125_a", "(III)I")) {
						AbstractInsnNode hi = Asm.prev(in);
						AbstractInsnNode lo = Asm.prev(hi);
						if (Asm.isInt(hi, 256) && Asm.isInt(lo, 64)) {
							m.instructions.set(hi, Asm.pushInt(MAX_Y));
							m.instructions.insert(in, new MethodInsnNode(INVOKESTATIC, CHUNK, "dhBuildLimit", "(I)I", false));
							++n;
						}
					}
				}
			}
			return n;
		}
	}

	static final class VoidPatch implements Patch0 {
		public int apply(ClassNode cn) {
			int n = 0;
			for (MethodNode m : cn.methods) {
				for (AbstractInsnNode in = m.instructions.getFirst(); in != null; in = in.getNext()) {
					if (in instanceof LdcInsnNode && Double.valueOf(-64.0D).equals(((LdcInsnNode) in).cst)) {
						((LdcInsnNode) in).cst = Double.valueOf(-128.0D);
						++n;
					}
				}
			}
			return n;
		}
	}

	//Vue 16 sections pour le code legacy
	static final class LegacyViewPatch implements Patch0 {
		public int apply(ClassNode cn) {
			int n = 0;
			for (MethodNode m : cn.methods) {
				for (AbstractInsnNode in = m.instructions.getFirst(); in != null; in = in.getNext()) {
					if (in.getOpcode() == INVOKEVIRTUAL && Asm.isCall(in, null, "func_76587_i", "()[Lnet/minecraft/world/chunk/storage/ExtendedBlockStorage;")) {
						MethodInsnNode mi = (MethodInsnNode) in;
						if (CHUNK.equals(mi.owner) || mi.owner.startsWith("net/minecraft/")) {
							m.instructions.set(in, in = new MethodInsnNode(INVOKESTATIC, CHUNK, "dhLegacyView", "(Lnet/minecraft/world/chunk/Chunk;)[Lnet/minecraft/world/chunk/storage/ExtendedBlockStorage;", false));
							++n;
						}
					}
				}
			}
			return n;
		}
	}

	//Superflat : vue 16 sections + couches sous Y 0
	static final class FlatProviderPatch implements Patch0 {
		public int apply(ClassNode cn) {
			int n = new LegacyViewPatch().apply(cn);
			Asm.check(n > 0, "ChunkProviderFlat getBlockStorageArray");
			MethodNode provide = Asm.require(cn, "func_73154_d", "(II)Lnet/minecraft/world/chunk/Chunk;");
			for (AbstractInsnNode in = provide.instructions.getFirst(); in != null; in = in.getNext()) {
				if (in.getOpcode() == INVOKEVIRTUAL && Asm.isCall(in, CHUNK, "func_76603_b", "()V")) {
					InsnList l = new InsnList();
					l.add(new InsnNode(DUP));
					l.add(new MethodInsnNode(INVOKESTATIC, CHUNK, "dhFlatExtend", "(Lnet/minecraft/world/chunk/Chunk;)V", false));
					provide.instructions.insertBefore(in, l);
					return n + 1;
				}
			}
			throw new IllegalStateException("generateSkylightMap introuvable dans provideChunk");
		}
	}

	//CraftBukkit

	//Retrait du y & 255
	static int removeYMask(MethodNode m, int ySlot, String owner) {
		int n = 0;
		AbstractInsnNode in = m.instructions.getFirst();
		while (in != null) {
			AbstractInsnNode next = in.getNext();
			boolean loadY = ySlot >= 0 ? Asm.isIload(in, ySlot) : Asm.isField(in, GETFIELD, owner, "y");
			if (loadY) {
				AbstractInsnNode c = Asm.next(in);
				AbstractInsnNode and = Asm.next(c);
				if (Asm.isInt(c, 255) && and != null && and.getOpcode() == IAND) {
					next = and.getNext();
					m.instructions.remove(c);
					m.instructions.remove(and);
					++n;
				}
			}
			in = next;
		}
		return n;
	}

	static final class CraftWorldPatch implements Patch0 {
		public int apply(ClassNode cn) {
			int n = 0;
			MethodNode get = Asm.method(cn, "getBlockAt", "(III)Lorg/bukkit/block/Block;");
			if (get != null) {
				n += removeYMask(get, 2, null);
			}
			MethodNode refresh = Asm.method(cn, "refreshChunk", "(II)Z");
			if (refresh != null) {
				for (AbstractInsnNode in = refresh.instructions.getFirst(); in != null; in = in.getNext()) {
					if (Asm.isCall(in, cn.name, "getMaxHeight", "()I")) {
						AbstractInsnNode c = Asm.next(in);
						AbstractInsnNode div = Asm.next(c);
						if (Asm.isInt(c, 16) && div != null && div.getOpcode() == IDIV) {
							InsnList l = new InsnList();
							l.add(new InsnNode(POP));
							l.add(Asm.pushInt(SECTIONS));
							refresh.instructions.insert(div, l);
							++n;
						}
					} else if (in.getOpcode() == IMUL && Asm.isInt(Asm.prev(in), 16)) {
						AbstractInsnNode before = Asm.prev(Asm.prev(in));
						AbstractInsnNode after = Asm.next(in);
						if (before != null && before.getOpcode() == IREM) {
							InsnList l = new InsnList();
							l.add(new IntInsnNode(BIPUSH, 64));
							l.add(new InsnNode(ISUB));
							refresh.instructions.insert(in, l);
							++n;
						} else if (Asm.isInt(after, 1) && Asm.next(after) != null && Asm.next(after).getOpcode() == ISUB) {
							InsnList l = new InsnList();
							l.add(new IntInsnNode(BIPUSH, 64));
							l.add(new InsnNode(ISUB));
							refresh.instructions.insert(Asm.next(after), l);
							++n;
						}
					}
				}
			}
			return n;
		}
	}

	static final class CraftChunkPatch implements Patch0 {
		public int apply(ClassNode cn) {
			int n = 0;
			MethodNode get = Asm.method(cn, "getBlock", "(III)Lorg/bukkit/block/Block;");
			if (get != null) {
				n += removeYMask(get, 2, null);
			}
			for (MethodNode m : cn.methods) {
				if (!"getEmptyChunkSnapshot".equals(m.name)) {
					continue;
				}
				for (AbstractInsnNode in = m.instructions.getFirst(); in != null; in = in.getNext()) {
					if (Asm.isCall(in, null, "getMaxHeight", "()I")) {
						AbstractInsnNode c = Asm.next(in);
						AbstractInsnNode shr = Asm.next(c);
						if (Asm.isInt(c, 4) && shr != null && shr.getOpcode() == ISHR) {
							InsnList l = new InsnList();
							l.add(new InsnNode(POP));
							l.add(Asm.pushInt(SECTIONS));
							m.instructions.insert(shr, l);
							++n;
						}
					}
				}
			}
			return n;
		}
	}

	static final class CraftBlockPatch implements Patch0 {
		public int apply(ClassNode cn) {
			int n = 0;
			for (MethodNode m : cn.methods) {
				n += removeYMask(m, -1, cn.name);
			}
			return n;
		}
	}

	//ChunkSnapshot en 24 sections
	static final class ChunkSnapshotPatch implements Patch0 {
		public int apply(ClassNode cn) {
			int n = 0;
			for (MethodNode m : cn.methods) {
				if ("(III)I".equals(m.desc)) {
					for (AbstractInsnNode in = m.instructions.getFirst(); in != null; in = in.getNext()) {
						if (Asm.isIload(in, 2) && Asm.isInt(Asm.next(in), 4)) {
							AbstractInsnNode shr = Asm.next(Asm.next(in));
							AbstractInsnNode load = Asm.next(shr);
							if (shr != null && shr.getOpcode() == ISHR && load != null && load.getOpcode() == AALOAD) {
								InsnList l = new InsnList();
								l.add(new InsnNode(ICONST_4));
								l.add(new InsnNode(IADD));
								m.instructions.insert(shr, l);
								++n;
							}
						}
					}
				} else if ("isSectionEmpty".equals(m.name) && "(I)Z".equals(m.desc)) {
					for (AbstractInsnNode in = m.instructions.getFirst(); in != null; in = in.getNext()) {
						if (Asm.isIload(in, 1) && Asm.next(in) != null && Asm.next(in).getOpcode() == BALOAD) {
							InsnList l = new InsnList();
							l.add(new InsnNode(ICONST_4));
							l.add(new InsnNode(IADD));
							m.instructions.insert(in, l);
							++n;
						}
					}
				}
			}
			return n;
		}
	}

	//Clic dans le vide : Y 256 n'est plus une sentinelle
	static final class InteractEventPatch implements Patch0 {
		public int apply(ClassNode cn) {
			int n = 0;
			for (MethodNode m : cn.methods) {
				if (!"callPlayerInteractEvent".equals(m.name)) {
					continue;
				}
				for (AbstractInsnNode in = m.instructions.getFirst(); in != null; in = in.getNext()) {
					if (Asm.isInt(in, 256) && m.desc.endsWith("Lnet/minecraft/item/ItemStack;)Lorg/bukkit/event/player/PlayerInteractEvent;") && m.desc.startsWith("(Lnet/minecraft/entity/player/EntityPlayer;Lorg/bukkit/event/block/Action;Lnet")) {
						m.instructions.set(in, in = Asm.pushInt(100000));
						++n;
					} else if (Asm.isInt(in, 255) && Asm.isIload(Asm.prev(in), 3)) {
						AbstractInsnNode j = Asm.next(in);
						if (j != null && j.getOpcode() >= IF_ICMPEQ && j.getOpcode() <= IF_ICMPLE) {
							m.instructions.set(in, in = Asm.pushInt(99999));
							++n;
						}
					}
				}
			}
			return n;
		}
	}

	interface Patch0 extends DeltaHeightTransformer.Patch {
	}
}
