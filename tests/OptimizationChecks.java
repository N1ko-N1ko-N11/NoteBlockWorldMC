import io.github.noteblockworldmc.NoteblockWorldMCPlugin;
import io.github.noteblockworldmc.nbs.*;
import io.github.noteblockworldmc.pack.*;
import io.github.noteblockworldmc.playback.*;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import java.io.*;
import java.lang.reflect.*;
import java.net.URI;
import java.net.http.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.ZipFile;

/** Behavioral and resource-bound checks for the optimization pass, using the real audio implementation. */
public class OptimizationChecks extends RegressionChecks {
    static Object construct(String name, Class<?>[] types, Object... values)throws Exception {
        Constructor<?> c=Class.forName(name).getDeclaredConstructor(types);c.setAccessible(true);return c.newInstance(values);
    }
    static NbsSong song(String name,Map<Integer,List<PlayableNote>> notes,List<String> custom,TreeMap<Integer,Double> tempos)throws Exception {
        return (NbsSong)construct("io.github.noteblockworldmc.nbs.NbsSong",
            new Class<?>[]{String.class,String.class,double.class,int.class,Map.class,int.class,int.class,int.class,List.class,TreeMap.class,boolean.class,int.class,int.class},
            name,"",10.0,Math.max(20,notes.keySet().stream().mapToInt(Integer::intValue).max().orElse(20)),notes,0,0,0,custom,tempos,false,0,0);
    }
    static Object session(PlaybackManager playback,Player player)throws Exception {return ((Map<?,?>)field(playback,"sessions")).get(player.getUniqueId());}
    static String sha(byte[] bytes,String algorithm)throws Exception {return HexFormat.of().formatHex(MessageDigest.getInstance(algorithm).digest(bytes));}
    static void replace(Path path,byte[] bytes)throws Exception {
        Files.createDirectories(path.getParent());Path temp=Files.createTempFile(path.getParent(),"replace-",".tmp");
        Files.write(temp,bytes);Files.move(temp,path,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
    }
    static long audioEntries(ZipFile zip){return zip.stream().filter(e->e.getName().startsWith("assets/nbw/sounds/custom/")&&e.getName().endsWith(".ogg")).count();}
    static void compareAnalysis(NoteblockWorldMCPlugin plugin,NbsSong song,String id,UUID listener)throws Exception {
        var pack=plugin.getResourcePackService();Map<String,Double> manifest=pack.eventDurations(listener);
        var intervals=new ArrayList<double[]>();int excluded=0;boolean estimated=false;
        float audibleFloor=PlaybackManager.configuredAudibleFloor(plugin);
        for(int tick=song.nextNoteTick(-1);tick<=song.getLengthTicks();tick=song.nextNoteTick(tick)) {
            for(PlayableNote note:song.getNotesAt(tick)) {
                if(!(note.volume()>0f)||note.volume()<audibleFloor){excluded++;continue;}
                String suffix=note.octave()==0?"":note.octave()<0?"_m"+-note.octave():"_p"+note.octave();
                String base=note.isCustom()?ResourcePackService.customSoundEvent(id,song.getCustomSoundFiles().get(note.customIndex())):ResourcePackService.INSTRUMENT_NAMES[note.instrument()];
                double seconds=note.isCustom()||note.octave()!=0?manifest.getOrDefault(base+suffix,-1.0):-1;
                boolean shifted=note.octave()!=0&&seconds>0;
                if(seconds<=0) seconds=note.isCustom()?manifest.getOrDefault(base,-1.0):pack.sampleSeconds(note.instrument());
                if(note.isCustom()&&seconds<=0){excluded++;continue;}
                if(seconds<=0){seconds=1;estimated=true;}
                double pitch=shifted?note.pitch():Math.max(.5,Math.min(2,Math.scalb((double)note.pitch(),note.octave())));
                double start=song.getSecondsUpTo(tick);intervals.add(new double[]{start,start+seconds/pitch});
            }
        }
        int peak=0;double peakAt=0,area=0,end=song.getDurationSeconds();
        for(double[] interval:intervals) {
            int active=0;for(double[] other:intervals)if(other[0]<=interval[0]&&other[1]>interval[0])active++;
            if(active>peak){peak=active;peakAt=interval[0];}
            area+=interval[1]-interval[0];end=Math.max(end,interval[1]);
        }
        var report=PolyphonyAnalyzer.analyze(plugin,song,id,listener);
        check(report.peakVoices()==peak&&Math.abs(report.peakAtSeconds()-peakAt)<1e-8,"Analyzer peak matches independent interval counting");
        check(Math.abs(report.meanVoices()-area/end)<1e-8,"Analyzer mean matches summed voice durations");
        check(report.excludedNotes()==excluded&&report.estimated()==estimated,"Analyzer exclusion and fallback semantics are preserved");
    }
    public static void main(String[] args)throws Exception {
        Path data=Path.of(args[0]);Files.createDirectories(data);System.setProperty("nbw.test.data",data.toString());
        Player alice=new Player("Alice"),bob=new Player("Bob");Bukkit.getServer().players.addAll(List.of(alice,bob));
        var plugin=new NoteblockWorldMCPlugin();plugin.onEnable();drain(plugin);
        try {
            var playback=plugin.getPlaybackManager();var pack=plugin.getResourcePackService();var downloads=plugin.getDownloadManager();var config=plugin.getConfig();
            NbsSong quiet=song("Quiet",Map.of(0,List.of(new PlayableNote(0,1f,.01f,0,-1,0))),List.of(),new TreeMap<>());
            check(PlaybackManager.configuredAudibleFloor(plugin)==0f,"Zero audible floor is the default");
            check(PolyphonyAnalyzer.analyze(plugin,quiet,null).excludedNotes()==0,"Analyzer keeps positive low-volume notes");
            playback.start(alice,quiet);Object quietSession=session(playback,alice);int quietBefore=alice.gains.size();
            call(quietSession,"emitNote",0,1f,field(quietSession,"listeners"));
            check(alice.gains.size()==quietBefore+1,"Playback emits positive low-volume notes");playback.stop(alice.getUniqueId());
            byte[] base=ogg(),higher=OggTools.shiftOctaves(base,1);
            var vanilla=pack.getVanillaSounds();Path harp=data.resolve("vanilla-sounds/harp.ogg");replace(harp,base);
            Object first=call(vanilla,"sample","harp");double seconds=OggTools.readDurationSeconds(base);
            check(first!=null&&vanilla.get("harp").equals(harp),"Real vanilla lookup accepts a validated source");
            check(call(vanilla,"sample","harp")==first&&vanilla.sampleSeconds("harp")==seconds,"Repeated lookup and duration queries reuse the same validated revision");
            for(int octave=-3;octave<=3;octave++) {
                Object variant=call(vanilla,"shiftedSample",first,octave);byte[] reference=OggTools.shiftOctaves(base,octave);
                check((variant==null)==(reference==null),"Cached octave retains sample-rate and duration bounds: "+octave);
                if(variant!=null)check(Arrays.equals((byte[])call(variant,"bytes"),reference)
                    &&Math.abs((double)call(variant,"seconds")-OggTools.readDurationSeconds(reference))<1e-10,"Cached octave has exact bytes, CRC and timing: "+octave);
            }
            replace(harp,higher);Object replaced=call(vanilla,"sample","harp");
            check(replaced!=first&&Math.abs(vanilla.sampleSeconds("harp")-seconds/2)<1e-10,"Replacing a source with unchanged file count refreshes its cached timing");
            FileTime stamp=Files.getLastModifiedTime(harp);byte[] damaged=higher.clone();damaged[damaged.length-1]^=1;Files.write(harp,damaged);Files.setLastModifiedTime(harp,stamp);
            vanilla.resetLookupFailures();check(vanilla.get("harp")==null,"Explicit reload revalidates even same-size files with restored timestamps");
            replace(harp,base);vanilla.resetLookupFailures();pack.rebuild();
            check(pack.hasEvent("harp_p1"),"A repaired source restores octave events");
            replace(harp,damaged);pack.rebuild();check(!pack.hasEvent("harp_p1"),"Corrupt replacement is not served from a stale octave cache");
            replace(harp,base);pack.rebuild();

            Object cache=construct("io.github.noteblockworldmc.pack.SampleCache",new Class<?>[]{long.class},(long)base.length*2);
            Path one=data.resolve("cache/one.ogg"),two=data.resolve("cache/two.ogg"),three=data.resolve("cache/three.ogg");
            replace(one,base);replace(two,base);replace(three,base);Object second=call(cache,"read",two);call(cache,"read",one);call(cache,"read",three);
            check((long)field(cache,"bytes")<=base.length*2L,"Audio snapshot cache respects its byte budget");
            check(call(cache,"read",two)!=second,"Least-recently-used source snapshots are evicted");

            byte[] aBytes=nbs("A",List.of(),new int[][]{{0,0,0,45,100},{20,0,0,45,100}});
            byte[] bBytes=nbs("B",List.of(),new int[][]{{0,0,0,45,100}});
            String aId=downloads.importBytes(aBytes,"A.nbs"),bId=downloads.importBytes(bBytes,"B.nbs");drain(plugin);
            Path aSound=downloads.getSongDirectory(aId).resolve("sounds/tone.ogg"),bSound=downloads.getSongDirectory(bId).resolve("sounds/tone.ogg");
            replace(aSound,base);replace(bSound,base);setField(pack,"maxCustomSoundBytes",(long)base.length);pack.rebuild();
            String aEvent=ResourcePackService.customSoundEvent(aId,"tone.ogg"),bEvent=ResourcePackService.customSoundEvent(bId,"tone.ogg");
            check(!aEvent.equals(bEvent)&&pack.hasEvent(aEvent)&&pack.hasEvent(bEvent),"Both song events fit when their shared audio fits once");
            try(var zip=new ZipFile(data.resolve("resource-pack.zip").toFile())) {
                check(audioEntries(zip)==1,"Duplicate custom samples occupy one physical ZIP entry");
                String json=new String(zip.getInputStream(zip.getEntry("assets/nbw/sounds.json")).readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
                String asset="nbw:custom/"+sha(base,"SHA-256");
                check(json.contains("\""+aEvent+"\":")&&json.contains("\""+bEvent+"\":")&&json.indexOf(asset)!=json.lastIndexOf(asset),"Per-song events both reference the shared asset");
            }
            setField(pack,"maxCustomSoundBytes",7L*base.length);pack.rebuild();
            int variants=0;for(int octave=-3;octave<=3;octave++)if(OggTools.shiftOctaves(base,octave)!=null)variants++;
            try(var zip=new ZipFile(data.resolve("resource-pack.zip").toFile())){check(audioEntries(zip)==variants,"Identical songs share all fitting octave variants too");}
            check(pack.hasEvent(aEvent+"_p1")&&pack.hasEvent(bEvent+"_p1"),"Deduplication preserves both songs' extended events");
            String oldHash=pack.getPackSha1();Path oldFile=data.resolve("pack-generations/"+oldHash+".zip");FileTime oldTime=Files.getLastModifiedTime(oldFile);
            byte[] oldBytes=Files.readAllBytes(oldFile);pack.rebuild();
            check(oldHash.equals(pack.getPackSha1())&&oldTime.equals(Files.getLastModifiedTime(oldFile)),"Unchanged builds reuse the immutable generation file");
            check(sha(oldBytes,"SHA-1").equals(oldHash)&&pack.getPackSize()==oldBytes.length,"Advertised digest and size match the streamed ZIP");
            FileChannel transfer=(FileChannel)call(pack,"lookupPack","/pack.zip?v="+oldHash);
            replace(aSound,higher);pack.rebuild();check(!pack.getPackSha1().equals(oldHash),"Changed custom audio produces a new immutable generation");
            check(call(pack,"lookupPack","/pack.zip")!=null,"Browser-friendly pack URL resolves to the current generation");
            check(call(pack,"lookupPack","/pack.zip?v=../../secret")==null,"Pack lookup cannot select filesystem paths");
            @SuppressWarnings("unchecked") Map<String,Long> advertised=(Map<String,Long>)field(pack,"advertisedAt");
            advertised.put(oldHash,System.currentTimeMillis()-600_001);Bukkit.getScheduler().tick();drain(plugin);
            try(transfer){check(Arrays.equals(Channels.newInputStream(transfer).readAllBytes(),oldBytes),"An admitted transfer keeps its exact bytes through generation expiry");}
            call(pack,"expireGenerations",System.currentTimeMillis());
            check(call(pack,"lookupPack","/pack.zip?v="+oldHash)==null&&!Files.exists(oldFile),"Periodic cleanup expires old generations without requiring a rebuild");
            check(Files.isRegularFile(data.resolve("pack-generations/"+pack.getPackSha1()+".zip")),"Cleanup retains the current generation");

            int port=freePort();config.set("resource-pack.enabled",true);config.set("resource-pack.port",port);
            config.set("resource-pack.public-url","http://127.0.0.1:"+port+"/pack.zip");pack.loadHotSettings();drain(plugin);
            HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();
            URI uri=URI.create("http://127.0.0.1:"+port+"/pack.zip?v="+pack.getPackSha1());
            synchronized(field(pack,"buildLock")) {
                var response=client.sendAsync(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(2)).build(),HttpResponse.BodyHandlers.ofByteArray()).get(3,TimeUnit.SECONDS);
                check(response.statusCode()==200&&sha(response.body(),"SHA-1").equals(pack.getPackSha1()),"HTTP admission and transfers proceed while the build lock is held");
            }
            var head=client.send(HttpRequest.newBuilder(uri).method("HEAD",HttpRequest.BodyPublishers.noBody()).build(),HttpResponse.BodyHandlers.ofByteArray());
            check(head.statusCode()==200&&head.body().length==0&&head.headers().firstValueAsLong("Content-Length").orElse(-1)==pack.getPackSize(),"File-backed HEAD has the full length and no body");
            setField(pack,"maxCustomSoundBytes",2L*base.length);pack.rebuild();pack.send(alice);
            status(pack,alice,org.bukkit.event.player.PlayerResourcePackStatusEvent.Status.SUCCESSFULLY_LOADED);
            check(pack.hasEvent(aEvent)&&!pack.hasEvent(aEvent+"_p1"),"Fallback test installs a base sample without its optional octave");
            NbsSong fallback=song("Fallback",Map.of(0,List.of(new PlayableNote(-1,1,1,1,0,0))),List.of("tone.ogg"),new TreeMap<>());
            playback.start(alice,fallback,aId);Object fallbackSession=session(playback,alice);alice.pitches.clear();
            call(fallbackSession,"emitNote",0,1f,field(fallbackSession,"listeners"));
            check(alice.pitches.equals(List.of(2f)),"Custom base fallback keeps octave transposition in runtime pitch");playback.stop(alice.getUniqueId());
            config.set("resource-pack.enabled",false);pack.loadHotSettings();

            CountDownLatch occupied=new CountDownLatch(4),release=new CountDownLatch(1);
            for(int i=0;i<4;i++)plugin.getWorkPool().run(()->{occupied.countDown();try{release.await(5,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}});
            check(occupied.await(2,TimeUnit.SECONDS),"Cache cancellation test controls worker completion");
            try {
                playback.playDownloaded(alice,aId);playback.playDownloaded(bob,bId);
                Map<?,?> loads=(Map<?,?>)field(playback,"loadingSongs");Object unrelated=loads.get(bId),removed=loads.get(aId);
                playback.invalidateCache(aId);
                check(!loads.containsKey(aId)&&((CompletableFuture<?>)call(removed,"future")).isCancelled(),"Song invalidation cancels that song's parse");
                check(loads.get(bId)==unrelated&&!((CompletableFuture<?>)call(unrelated,"future")).isCancelled(),"Invalidation preserves other songs' in-flight parses");
                playback.invalidateSampleCaches();check(loads.get(bId)==unrelated,"Audio reload preserves pending note-data loads");
            } finally {release.countDown();}
            drain(plugin);check(session(playback,alice)==null&&session(playback,bob)!=null,"Cancelled callbacks stay cancelled while an unrelated song starts");
            Object songs=field(playback,"songCache"),cached=call(songs,"get",bId);playback.invalidateSampleCaches();
            check(call(songs,"get",bId)==cached,"Audio reload preserves cached parsed songs");playback.stop(bob.getUniqueId());
            playback.playDownloaded(alice,aId);playback.invalidateCache(aId);playback.playDownloaded(alice,aId);drain(plugin);
            check(session(playback,alice)!=null,"An old parse completion cannot consume its replacement's callback");playback.stop(alice.getUniqueId());

            NbsSong a=parse(aBytes),b=parse(bBytes);Object weighted=construct("io.github.noteblockworldmc.playback.SongCache",new Class<?>[]{long.class,int.class},a.estimatedRetainedBytes()+b.estimatedRetainedBytes()-1,16);
            call(weighted,"put","a",a);call(weighted,"put","b",b);
            check(call(weighted,"get","a")==null&&call(weighted,"get","b")==b,"Parsed-song memory budget evicts the oldest song");
            call(weighted,"put","b",b);check((long)field(weighted,"bytes")==b.estimatedRetainedBytes(),"Replacing a cached song does not double-count its memory");
            check(a.getNotesAt(0).size()==1,"Cache eviction leaves actively referenced song data usable");

            config.set("playback.fade-out-ms",0);config.set("playback.humanize",false);config.set("playback.soft-limiter",false);config.set("playback.voice-allocation",true);playback.loadHotSettings();
            var chord=List.of(new PlayableNote(0,.7f,.2f,0,-1,0),new PlayableNote(0,1.2f,.9f,0,-1,0),new PlayableNote(0,1.4f,.9f,0,-1,0),new PlayableNote(0,1.7f,.5f,0,-1,0));
            NbsSong ordered=song("Order",Map.of(0,chord),List.of(),new TreeMap<>());playback.start(alice,ordered);Object active=session(playback,alice);alice.pitches.clear();
            call(active,"emitChord",0,4,field(active,"listeners"));check(alice.pitches.equals(List.of(1.2f,1.4f,1.7f,.7f)),"Direct-array playback uses stable loudest-first ordering");playback.stop(alice.getUniqueId());
            config.set("playback.voice-allocation",false);playback.loadHotSettings();playback.start(alice,ordered);active=session(playback,alice);alice.pitches.clear();
            call(active,"emitChord",0,4,field(active,"listeners"));check(alice.pitches.equals(List.of(.7f,1.2f,1.4f,1.7f)),"Disabling voice allocation retains original note order");playback.stop(alice.getUniqueId());
            var dense=new ArrayList<PlayableNote>();for(int i=0;i<64;i++)dense.add(new PlayableNote(i%2==0?0:2,.5f+i/64f,.6f,0,-1,0));
            NbsSong human=song("Human",Map.of(0,dense),List.of(),new TreeMap<>());
            config.set("playback.humanize",true);config.set("playback.humanize-velocity",0);config.set("playback.humanize-drift",1);config.set("playback.humanize-timing-ms",6);playback.loadHotSettings();
            playback.start(alice,human);active=session(playback,alice);alice.pitches.clear();call(active,"scheduleHumanised",0,System.nanoTime());
            check(((Queue<?>)field(active,"pending")).size()<=2,"A dense humanized chord schedules at most two groups");
            call(active,"drainPending",System.nanoTime()+1_000_000_000L,field(active,"listeners"));
            check(alice.pitches.size()==64&&new HashSet<>(alice.pitches).size()==64,"Humanized groups emit each melodic and percussion note exactly once");playback.stop(alice.getUniqueId());

            // Compare optimized analysis with an independent, deliberately simple interval oracle.
            var notes=new TreeMap<Integer,List<PlayableNote>>();var random=new Random(7);
            for(int tick=0;tick<120;tick++) {
                var at=new ArrayList<PlayableNote>();for(int n=0;n<3;n++){boolean custom=random.nextBoolean();at.add(new PlayableNote(custom?-1:0,.5f+random.nextInt(4)*.5f,random.nextInt(8)==0?0:.6f,random.nextInt(7)-3,custom?0:-1,0));}notes.put(tick,at);
            }
            NbsSong arrangement=song("Analysis",notes,List.of("tone.ogg"),new TreeMap<>(Map.of(30,20.0,70,5.0)));
            compareAnalysis(plugin,arrangement,aId,null);compareAnalysis(plugin,arrangement,aId,UUID.randomUUID());
        } finally {plugin.onDisable();}
        System.out.println("PASS: "+checks+" optimization checks.");
    }
}
