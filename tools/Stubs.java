//Génère libs/mcpc-stubs.jar : signatures seules (aucun code) des classes MCPC utilisées par Chunk
//Permet de compiler le projet sans le jar du serveur (GitHub Actions)
//Lancé par : gradlew mcpcStubs (à refaire si Chunk.java utilise de nouvelles classes ou méthodes MCPC)
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import java.util.regex.*;
import java.util.zip.*;

public class Stubs {
	private static final Pattern TYPE = Pattern.compile("L([^;<>:]+)[;<]");
	private static final String CHUNK = "net/minecraft/world/chunk/Chunk";

	private static final Map<String, ClassNode> classes = new HashMap<>();
	private static final Set<String> needed = new TreeSet<>();
	private static final Map<String, Set<String>> members = new HashMap<>();

	//args : Chunk.class, stubs.jar, jars MCPC...
	public static void main(String[] args) throws Exception {
		for (int i = 2; i < args.length; i++) {
			load(Paths.get(args[i]));
		}
		ClassNode chunk = read(Files.readAllBytes(Paths.get(args[0])), 0);

		//Types et membres utilisés par Chunk
		addTypes(chunk.superName);
		for (String itf : chunk.interfaces) {
			addTypes(itf);
		}
		for (FieldNode f : chunk.fields) {
			addTypes(f.desc);
			addTypes(f.signature);
		}
		for (MethodNode m : chunk.methods) {
			addTypes(m.desc);
			addTypes(m.signature);
			for (String e : m.exceptions) {
				addTypes(e);
			}
			for (TryCatchBlockNode t : m.tryCatchBlocks) {
				addTypes(t.type);
			}
			if (m.localVariables != null) {
				for (LocalVariableNode l : m.localVariables) {
					addTypes(l.desc);
					addTypes(l.signature);
				}
			}
			for (AbstractInsnNode in = m.instructions.getFirst(); in != null; in = in.getNext()) {
				if (in instanceof TypeInsnNode) {
					addTypes(((TypeInsnNode) in).desc);
				} else if (in instanceof FieldInsnNode) {
					FieldInsnNode f = (FieldInsnNode) in;
					addMember(f.owner, f.name, f.desc);
				} else if (in instanceof MethodInsnNode) {
					MethodInsnNode mi = (MethodInsnNode) in;
					addMember(mi.owner, mi.name, mi.desc);
				} else if (in instanceof MultiANewArrayInsnNode) {
					addTypes(((MultiANewArrayInsnNode) in).desc);
				} else if (in instanceof LdcInsnNode && ((LdcInsnNode) in).cst instanceof Type) {
					addTypes(((Type) ((LdcInsnNode) in).cst).getDescriptor());
				} else if (in instanceof FrameNode) {
					addFrame(((FrameNode) in).local);
					addFrame(((FrameNode) in).stack);
				}
			}
		}

		//Classes parentes, classes englobantes et types des membres gardés
		boolean changed = true;
		while (changed) {
			changed = false;
			for (String name : new ArrayList<>(needed)) {
				ClassNode cn = classes.get(name);
				int before = needed.size();
				addTypes(cn.superName);
				for (String itf : cn.interfaces) {
					addTypes(itf);
				}
				addTypes(cn.signature);
				for (InnerClassNode ic : cn.innerClasses) {
					if (ic.name.equals(name) && ic.outerName != null) {
						addTypes(ic.outerName);
					}
				}
				for (FieldNode f : cn.fields) {
					if (kept(name, f.name, f.desc)) {
						addTypes(f.desc);
						addTypes(f.signature);
					}
				}
				for (MethodNode m : cn.methods) {
					if (kept(name, m.name, m.desc)) {
						addTypes(m.desc);
						addTypes(m.signature);
						for (String e : m.exceptions) {
							addTypes(e);
						}
					}
				}
				changed |= needed.size() != before;
			}
		}

		//Écriture du jar
		try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(Paths.get(args[1])))) {
			for (String name : needed) {
				JarEntry entry = new JarEntry(name + ".class");
				entry.setTime(0L);
				out.putNextEntry(entry);
				out.write(stub(classes.get(name)));
				out.closeEntry();
			}
		}
		System.out.println("Stubs : " + needed.size() + " classes -> " + args[1]);
	}

	private static void load(Path jar) throws IOException {
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			for (Enumeration<? extends ZipEntry> e = zip.entries(); e.hasMoreElements();) {
				ZipEntry entry = e.nextElement();
				String n = entry.getName();
				if (!n.endsWith(".class")) {
					continue;
				}
				String name = n.substring(0, n.length() - 6);
				//Le premier jar est prioritaire (noms SRG)
				if (!classes.containsKey(name)) {
					try (InputStream in = zip.getInputStream(entry)) {
						classes.put(name, read(in.readAllBytes(), ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES));
					}
				}
			}
		}
	}

	private static ClassNode read(byte[] bytes, int flags) {
		ClassNode cn = new ClassNode();
		new ClassReader(bytes).accept(cn, flags);
		return cn;
	}

	private static void addTypes(String s) {
		if (s == null) {
			return;
		}
		if (!s.contains(";") && !s.contains("(")) {
			addClass(s.startsWith("[") ? null : s);
			return;
		}
		Matcher m = TYPE.matcher(s);
		while (m.find()) {
			addClass(m.group(1));
		}
	}

	private static void addFrame(List<Object> values) {
		if (values != null) {
			for (Object o : values) {
				if (o instanceof String) {
					String s = (String) o;
					addTypes(s.startsWith("[") ? s : "L" + s + ";");
				}
			}
		}
	}

	private static void addClass(String name) {
		if (name != null && !name.equals(CHUNK) && classes.containsKey(name)) {
			needed.add(name);
		}
	}

	private static void addMember(String owner, String name, String desc) {
		if (owner.startsWith("[")) {
			return;
		}
		addClass(owner);
		addTypes(desc);
		String declaring = declaring(owner, name, desc);
		if (declaring != null) {
			addClass(declaring);
			members.computeIfAbsent(declaring, k -> new HashSet<>()).add(name + desc);
		}
	}

	//Classe qui déclare vraiment le membre (classe parente ou interface)
	private static String declaring(String owner, String name, String desc) {
		ClassNode cn = classes.get(owner);
		if (cn == null) {
			return null;
		}
		for (FieldNode f : cn.fields) {
			if (f.name.equals(name) && f.desc.equals(desc)) {
				return owner;
			}
		}
		for (MethodNode m : cn.methods) {
			if (m.name.equals(name) && m.desc.equals(desc)) {
				return owner;
			}
		}
		String found = cn.superName != null ? declaring(cn.superName, name, desc) : null;
		for (int i = 0; found == null && i < cn.interfaces.size(); i++) {
			found = declaring(cn.interfaces.get(i), name, desc);
		}
		return found;
	}

	private static boolean kept(String owner, String name, String desc) {
		Set<String> s = members.get(owner);
		return s != null && s.contains(name + desc);
	}

	private static byte[] stub(ClassNode src) {
		ClassNode cn = new ClassNode();
		cn.version = Math.min(src.version, Opcodes.V1_8);
		cn.access = src.access;
		cn.name = src.name;
		cn.signature = src.signature;
		cn.superName = src.superName;
		cn.interfaces = new ArrayList<>(src.interfaces);
		for (InnerClassNode ic : src.innerClasses) {
			if (needed.contains(ic.name) && (ic.outerName == null || needed.contains(ic.outerName))) {
				cn.innerClasses.add(ic);
			}
		}
		for (FieldNode f : src.fields) {
			if (kept(src.name, f.name, f.desc)) {
				cn.fields.add(new FieldNode(f.access, f.name, f.desc, f.signature, f.value));
			}
		}
		for (MethodNode m : src.methods) {
			if (!kept(src.name, m.name, m.desc)) {
				continue;
			}
			MethodNode s = new MethodNode(m.access, m.name, m.desc, m.signature, m.exceptions.toArray(new String[0]));
			if ((m.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) == 0) {
				s.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
				s.instructions.add(new InsnNode(Opcodes.ATHROW));
				s.maxStack = 1;
				s.maxLocals = Type.getArgumentsAndReturnSizes(m.desc) >> 2;
			}
			cn.methods.add(s);
		}
		ClassWriter cw = new ClassWriter(0);
		cn.accept(cw);
		return cw.toByteArray();
	}
}
