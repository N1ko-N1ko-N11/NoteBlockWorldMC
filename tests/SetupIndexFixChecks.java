import io.github.noteblockworldmc.NoteblockWorldMCPlugin;
import io.github.noteblockworldmc.pack.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Real production setup parsing/pack building and deterministic metadata-index races. */
public final class SetupIndexFixChecks extends RegressionChecks {
    public static void main(String[] args) throws Exception {
        Path data=Path.of(args[0]); Files.createDirectories(data);
        System.setProperty("nbw.test.data",data.toString());
        byte[] song=nbs("Existing Song",List.of(),new int[][]{{0,0,0,45,100}});
        for (String id:List.of("old","victim")) {
            Path dir=data.resolve("songs/"+id); Files.createDirectories(dir);
            Files.write(dir.resolve("song.nbs"),song);
            Files.writeString(dir.resolve("meta.json"),id);
        }
        com.google.gson.Gson.readResult=s-> switch(s) {
            case "old" -> Map.of("title","Existing Song");
            case "victim" -> Map.of("title","Removed Song");
            default -> null;
        };
        CountDownLatch entered=new CountDownLatch(1), release=new CountDownLatch(1);
        AtomicBoolean block=new AtomicBoolean(true);
        AtomicInteger reads=new AtomicInteger();
        com.google.gson.Gson.beforeRead=s->{
            reads.incrementAndGet();
            if(block.getAndSet(false)) {
                entered.countDown();
                try { if(!release.await(4,TimeUnit.SECONDS)) throw new AssertionError("Index blocked"); }
                catch(InterruptedException e){throw new RuntimeException(e);}
            }
        };
        var plugin=new NoteblockWorldMCPlugin(); plugin.onEnable();
        try {
            check(entered.await(3,TimeUnit.SECONDS),"Startup title scan blocked outside manager lock");
            var downloads=plugin.getDownloadManager();
            String added=downloads.importBytes(song,"new.nbs","New Song");
            check(downloads.remove("victim"),"Removal succeeds while indexing is blocked");
            release.countDown(); drain(plugin);
            check(downloads.resolve("Existing Song").equals(List.of("old")),"Startup retries unrelated mutation without reload");
            check(downloads.resolve("New Song").equals(List.of(added)),"New import title survives retry");
            check(downloads.resolve("Removed Song").isEmpty(),"Removed song title is not resurrected");
            check(!(boolean)field(downloads,"startupTitlesPending"),"Successful startup index clears pending flag");

            setField(downloads,"startupTitlesPending",true);
            ((Map<?,?>)field(downloads,"titles")).clear();
            com.google.gson.Gson.beforeRead=s->{
                try { synchronized(downloads) {setField(downloads,"libraryGeneration",(long)field(downloads,"libraryGeneration")+1);} }
                catch(Exception e){throw new RuntimeException(e);}
            };
            call(downloads,"indexStartupTitles");
            check((boolean)field(downloads,"startupTitlesPending"),"Sustained mutations retain deferred indexing work");
            com.google.gson.Gson.beforeRead=s->{};
            org.bukkit.Bukkit.getScheduler().tick(); drain(plugin);
            check(downloads.resolve("Existing Song").equals(List.of("old")),"Maintenance retries exhausted startup scan");

            byte[] valid=ogg(), bad=valid.clone();
            int setup=signature(bad,5); check(setup>=0,"Fixture setup located");
            bad[setup+8]=0; bad[setup+9]=0; bad[setup+10]=0; fixCrc(bad);
            check(OggTools.isVorbis(valid),"Valid fixture is accepted");
            check(!OggTools.isVorbis(bad),"Corrupt codebook with valid page CRC is rejected");
            check(OggTools.shiftOctaves(bad,0)==null && OggTools.shiftOctaves(bad,1)==null,"Shifting cannot bypass validation");
            check(OggTools.shiftOctaves(valid,1)!=null,"Valid octave shifting still works");
            Path sounds=data.resolve("songs/old/sounds"); Files.createDirectories(sounds);
            Files.write(sounds.resolve("broken.ogg"),bad); Files.write(sounds.resolve("valid.ogg"),valid);
            plugin.getResourcePackService().rebuild();
            check(!plugin.getResourcePackService().hasEvent(ResourcePackService.customSoundEvent("old","broken.ogg")),"Bad setup excluded from generated pack");
            check(plugin.getResourcePackService().hasEvent(ResourcePackService.customSoundEvent("old","valid.ogg")),"Valid sample retained in generated pack");
            Path fixtures=Path.of(System.getProperty("nbw.test.fixture")).getParent();
            try(var files=Files.list(fixtures)) {
                for(Path sample:files.filter(p->p.toString().endsWith(".ogg")).toList()) {
                    byte[] bytes=Files.readAllBytes(sample);
                    check(OggTools.isVorbis(bytes),"Real Vorbis corpus accepted: "+sample.getFileName());
                }
            }
            setupTests();
        } finally {
            release.countDown(); com.google.gson.Gson.beforeRead=s->{};
            com.google.gson.Gson.readResult=s->null; plugin.onDisable();
        }
        System.out.println("PASS: "+checks+" setup/index regression checks.");
    }
    static int signature(byte[] data,int type) {
        for(int i=0;i<data.length-7;i++) if(data[i]==type && new String(data,i+1,6,java.nio.charset.StandardCharsets.US_ASCII).equals("vorbis")) return i;
        return -1;
    }
    static void fixCrc(byte[] data)throws Exception {
        var crc=OggTools.class.getDeclaredMethod("fixPageCrc",byte[].class,int.class); crc.setAccessible(true);
        for(int at=0;at<data.length;) {
            int count=data[at+26]&255,end=at+27+count;
            for(int j=0;j<count;j++) end+=data[at+27+j]&255;
            crc.invoke(null,data,at);at=end;
        }
    }
    static final class Bits {
        byte[] data=new byte[256]; int position=56;
        final Map<String,int[]> fields=new LinkedHashMap<>();
        void put(String name,int value,int width) {
            fields.put(name,new int[]{position,width}); set(data,position,value,width);position+=width;
        }
        static void set(byte[] data,int position,int value,int width) {
            for(int bit=0;bit<width;bit++) {
                int at=position+bit,mask=1<<(at%8);
                data[at/8]=(byte)((data[at/8]&~mask)|(((value>>>bit)&1)<<(at%8)));
            }
        }
        byte[] bytes(){return Arrays.copyOf(data,(position+7)/8);}
    }
    static void setupTests()throws Exception {
        var parse=Class.forName("io.github.noteblockworldmc.pack.VorbisSetup").getDeclaredMethod("read",byte[].class,int.class);parse.setAccessible(true);
        Bits b=new Bits();
        b.put("books",0,8);b.put("sync",0x564342,24);b.put("dimensions",1,16);b.put("entries",1,24);
        b.put("ordered",1,1);b.put("length",0,5);b.put("run",1,1);b.put("lookup",1,4);
        b.put("minimum",0,32);b.put("delta",0,32);b.put("width",0,4);b.put("sequence",0,1);b.put("value",0,1);
        b.put("times",0,6);b.put("time",0,16);b.put("floors",0,6);b.put("floor",1,16);
        b.put("partitions",0,5);b.put("multiplier",0,2);b.put("range",1,4);
        b.put("residues",0,6);b.put("residue",0,16);b.put("begin",0,24);b.put("end",0,24);b.put("partitionSize",0,24);
        b.put("classes",0,6);b.put("classbook",0,8);b.put("cascadeLow",0,3);b.put("cascadeFlag",0,1);
        b.put("mappings",0,6);b.put("mapping",0,16);b.put("submaps",0,1);b.put("coupling",0,1);b.put("reserved",0,2);
        b.put("unusedTime",0,8);b.put("floorIndex",0,8);b.put("residueIndex",0,8);
        b.put("modes",0,6);b.put("longBlock",0,1);b.put("window",0,16);b.put("transform",0,16);b.put("mappingIndex",0,8);b.put("framing",1,1);
        byte[] good=b.bytes(); check(parse.invoke(null,good,1)!=null,"Minimal bounded setup accepted");
        Map<String,Integer> invalid=Map.ofEntries(
            Map.entry("sync",0),Map.entry("dimensions",0),Map.entry("entries",0),Map.entry("length",1),
            Map.entry("lookup",3),Map.entry("time",1),Map.entry("floor",2),Map.entry("residue",3),
            Map.entry("begin",1),Map.entry("classbook",1),Map.entry("mapping",1),Map.entry("reserved",1),
            Map.entry("floorIndex",1),Map.entry("residueIndex",1),Map.entry("window",1),Map.entry("transform",1),
            Map.entry("mappingIndex",1),Map.entry("framing",0));
        for(var entry:invalid.entrySet()) {
            byte[] bad=good.clone();int[] field=b.fields.get(entry.getKey());Bits.set(bad,field[0],entry.getValue(),field[1]);
            check(parse.invoke(null,bad,1)==null,"Reject invalid setup field: "+entry.getKey());
        }
        byte[] huge=good.clone();int[] entries=b.fields.get("entries");Bits.set(huge,entries[0],0xffffff,24);
        check(parse.invoke(null,huge,1)==null,"Reject oversized codebook before entry scan");
        for(int size=0;size<good.length;size++) check(parse.invoke(null,Arrays.copyOf(good,size),1)==null,"Reject truncated setup at "+size);
    }
}
