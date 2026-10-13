import java.io.*;import java.nio.file.*;
import brut.androlib.src.SmaliBuilder;
import com.android.tools.smali.dexlib2.Opcodes;
import com.android.tools.smali.dexlib2.writer.builder.DexBuilder;
import com.android.tools.smali.dexlib2.writer.io.FileDataStore;
public class Assemble {public static void main(String[] args)throws Exception {
 Path root=Paths.get(args[0]);SmaliBuilder s=new SmaliBuilder(root.toFile(),28);DexBuilder dex=new DexBuilder(new Opcodes(28,0));
 Path overlay=args.length>2?Paths.get(args[2]):null;
 SmaliBuilder patched=overlay==null?null:new SmaliBuilder(overlay.toFile(),28);
 try(var paths=Files.walk(root)){paths.filter(p->p.toString().endsWith(".smali")).sorted().forEach(p->{
  Path relative=root.relativize(p);
  SmaliBuilder builder=overlay!=null&&Files.isRegularFile(overlay.resolve(relative))?patched:s;
  builder.buildFile(relative.toString(),dex);
 });}
 dex.writeTo(new FileDataStore(new File(args[1])));
}}
