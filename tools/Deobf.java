//Génère libs/mcpc-srg.jar (jar MCPC en noms SRG), utile seulement pour régénérer libs/mcpc-stubs.jar
//1. python -c "import lzma,zipfile,os;os.makedirs('build',exist_ok=True);open('build/srg.txt','wb').write(lzma.decompress(zipfile.ZipFile('libs/minecraft_server.jar').read('deobfuscation_data-1.6.4.lzma')))"
//2. gradlew mcpcSrg
import org.objectweb.asm.*;
import org.objectweb.asm.commons.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

public class Deobf {
	static Map<String,String> cls = new HashMap<>();
	static Map<String,String> fld = new HashMap<>();
	static Map<String,String> mth = new HashMap<>();
	static Map<String,String[]> hier = new HashMap<>();
	public static void main(String[] a) throws Exception {
		for (String l : Files.readAllLines(Paths.get(a[0]))) {
			String[] p = l.split(" ");
			if (l.startsWith("CL:")) cls.put(p[1], p[2]);
			else if (l.startsWith("FD:")) { fld.put(p[1], p[2].substring(p[2].lastIndexOf('/')+1)); }
			else if (l.startsWith("MD:")) { mth.put(p[1]+" "+p[2], p[3].substring(p[3].lastIndexOf('/')+1)); }
		}
		ZipFile zf = new ZipFile(a[1]);
		Map<String,byte[]> classes = new LinkedHashMap<>();
		for (Enumeration<? extends ZipEntry> e = zf.entries(); e.hasMoreElements();) {
			ZipEntry ze = e.nextElement();
			if (!ze.getName().endsWith(".class")) continue;
			String n = ze.getName().substring(0, ze.getName().length()-6);
			boolean want = !n.contains("/") || n.startsWith("net/minecraft/") || n.startsWith("net/minecraftforge/") || n.startsWith("cpw/") || n.startsWith("org/bukkit/") || n.startsWith("org/spigotmc/") || n.startsWith("za/");
			if (!want) continue;
			byte[] b = zf.getInputStream(ze).readAllBytes();
			classes.put(n, b);
			ClassReader cr = new ClassReader(b);
			String[] itf = cr.getInterfaces();
			String[] h = new String[itf.length+1]; h[0]=cr.getSuperName(); System.arraycopy(itf,0,h,1,itf.length);
			hier.put(n, h);
		}
		Remapper r = new Remapper() {
			public String map(String n) { return cls.getOrDefault(n, n); }
			public String mapFieldName(String o, String n, String d) { String x = find(o, n, null); return x==null?n:x; }
			public String mapMethodName(String o, String n, String d) { if (n.startsWith("<")) return n; String x = find(o, n, d); return x==null?n:x; }
			String find(String o, String n, String d) {
				Deque<String> q = new ArrayDeque<>(); q.add(o); Set<String> seen = new HashSet<>();
				while(!q.isEmpty()) { String c = q.poll(); if (c==null||!seen.add(c)) continue;
					String v = d==null? fld.get(c+"/"+n) : mth.get(c+"/"+n+" "+d);
					if (v!=null) return v;
					String[] h = hier.get(c); if (h!=null) for (String s: h) q.add(s);
				}
				return null;
			}
		};
		try (ZipOutputStream zo = new ZipOutputStream(new FileOutputStream(a[2]))) {
			for (Map.Entry<String,byte[]> en : classes.entrySet()) {
				try {
				ClassReader cr = new ClassReader(en.getValue());
				ClassWriter cw = new ClassWriter(0);
				cr.accept(new ClassRemapper(cw, r), 0);
				zo.putNextEntry(new ZipEntry(r.map(en.getKey())+".class"));
				zo.write(cw.toByteArray()); zo.closeEntry();
				} catch (Throwable t) { System.err.println(en.getKey()+" "+t); }
			}
		}
	}
}
