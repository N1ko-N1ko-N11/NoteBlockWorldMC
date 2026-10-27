import io.github.noteblockworldmc.NoteblockWorldMCPlugin;
import io.github.noteblockworldmc.download.ImportFiles;
import io.github.noteblockworldmc.nbs.*;
import io.github.noteblockworldmc.pack.*;
import io.github.noteblockworldmc.playback.*;
import io.github.noteblockworldmc.util.ConfigEditor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;
import static org.bukkit.event.player.PlayerResourcePackStatusEvent.Status.*;
import java.io.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.net.ServerSocket;
import java.util.*;
import java.util.zip.ZipFile;

/** Focused checks against production classes; external APIs are replaced by test doubles. */
public class RegressionChecks {
    static int checks;
    static void check(boolean ok,String message){checks++;if(!ok)throw new AssertionError(message);}
    static Object field(Object target,String name)throws Exception {
        Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(target);
    }
    static void setField(Object target,String name,Object value)throws Exception {
        Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);f.set(target,value);
    }
    static Object call(Object target,String name,Object...args)throws Exception {
        for(Method m:target.getClass().getDeclaredMethods())if(m.getName().equals(name)&&m.getParameterCount()==args.length){
            m.setAccessible(true);try{return m.invoke(target,args);}catch(InvocationTargetException e){throw new RuntimeException(e.getCause());}
        }
        throw new NoSuchMethodException(name);
    }
    static void rejectsImport(Path root,String name)throws Exception {
        try{ImportFiles.select(root,name);throw new AssertionError("Accepted "+name);}catch(IOException expected){checks++;}
    }
    static int freePort()throws IOException {try(var s=new ServerSocket(0)){return s.getLocalPort();}}
    static void status(ResourcePackService pack,Player p,PlayerResourcePackStatusEvent.Status status){
        var request=p.packs.get(p.packs.size()-1);
        pack.onPackStatus(new PlayerResourcePackStatusEvent(p,request.info.id(),status));
    }
    static long warnings(Player p,String key){return p.messages.stream().filter(c->c.value.startsWith(key)).count();}
    static void finishFade(PlaybackManager playback)throws Exception {
        Object old=field(playback,"broadcastSession");
        setField(old,"fadeStartNanos",System.nanoTime()-2_000_000_000L);
        call(old,"step");Bukkit.getScheduler().drain();
    }
    static void shortLE(OutputStream o,int x)throws IOException{o.write(x&255);o.write((x>>>8)&255);}
    static void intLE(OutputStream o,int x)throws IOException{shortLE(o,x);shortLE(o,x>>>16);}
    static void string(OutputStream o,String s)throws IOException{byte[] b=s.getBytes(StandardCharsets.UTF_8);intLE(o,b.length);o.write(b);}
    // rows: tick, layer, instrument, key, pan[, velocity]. Header length intentionally leaves room for fades.
    static byte[] nbs(String name,List<String> sounds,int[][] notes)throws Exception {
        var out=new ByteArrayOutputStream();int layers=Arrays.stream(notes).mapToInt(n->n[1]+1).max().orElse(1);
        shortLE(out,0);out.write(6);out.write(20);shortLE(out,20);shortLE(out,layers);
        string(out,name);string(out,"Author");string(out,"");string(out,"");shortLE(out,1000);
        out.write(new byte[3]);out.write(new byte[20]);string(out,"");out.write(0);out.write(0);shortLE(out,0);
        int previousTick=-1,lastLayer=-1,currentTick=-1;
        for(int[] n:notes){
            if(n[0]!=currentTick){if(currentTick>=0)shortLE(out,0);shortLE(out,n[0]-previousTick);previousTick=n[0];currentTick=n[0];lastLayer=-1;}
            shortLE(out,n[1]-lastLayer);lastLayer=n[1];out.write(n[2]);out.write(n[3]);out.write(n.length>5?n[5]:100);out.write(n[4]);shortLE(out,0);
        }
        if(currentTick>=0)shortLE(out,0);shortLE(out,0);
        for(int i=0;i<layers;i++){string(out,"");out.write(0);out.write(100);out.write(100);}
        out.write(sounds.size());for(String f:sounds){string(out,"Custom");string(out,f);out.write(45);out.write(0);}
        return out.toByteArray();
    }
    static NbsSong parse(byte[] bytes)throws Exception{return NbsParser.parse(new ByteArrayInputStream(bytes));}
    static byte[] ogg() {
        try { return Files.readAllBytes(Path.of(System.getProperty("nbw.test.fixture", "tests/fixtures/tone.ogg"))); }
        catch (IOException e) { throw new java.io.UncheckedIOException(e); }
    }
    static void drain(NoteblockWorldMCPlugin plugin)throws Exception {
        long until=System.nanoTime()+5_000_000_000L; int quiet=0;
        while(System.nanoTime()<until){
            Bukkit.getScheduler().drain();
            boolean idle=((Set<?>)field(plugin.getWorkPool(),"pending")).isEmpty() && Bukkit.getScheduler().queued.isEmpty();
            if(idle && ++quiet>=3)return;if(!idle)quiet=0;Thread.sleep(5);
        }
        throw new AssertionError("Workers did not settle");
    }
    public static void main(String[] args)throws Exception {
        Path data=Path.of(args[0]);Files.createDirectories(data);System.setProperty("nbw.test.data",data.toString());
        Path imports=data.resolve("selection");Files.createDirectories(imports);
        Path chosen=Files.write(imports.resolve("My Song.nbs"),new byte[]{1});Files.write(imports.resolve("Another.NBS"),new byte[]{2});
        Files.write(imports.resolve("notes.txt"),new byte[]{3});Files.createDirectory(imports.resolve("folder.nbs"));
        check(ImportFiles.select(imports,"My Song.nbs").equals(List.of(chosen)),"Named import selects only that file");
        check(ImportFiles.select(imports,"\"My Song.nbs\"").equals(List.of(chosen)),"Quoted filenames with spaces work");
        check(ImportFiles.select(imports,null).size()==2,"Bare import selects all top-level NBS files only");
        rejectsImport(imports,"missing.nbs");rejectsImport(imports,"../escape.nbs");rejectsImport(imports,"..\\escape.nbs");rejectsImport(imports,"notes.txt");
        Files.createSymbolicLink(imports.resolve("link.nbs"),chosen.toAbsolutePath());rejectsImport(imports,"link.nbs");
        check(ImportFiles.select(imports,null).size()==2,"Bulk import excludes symlinks");

        byte[] customBytes=nbs("Custom",List.of("drums/kick.ogg"),new int[][]{{0,0,20,45,100},{0,1,20,45,100}});
        NbsSong custom=parse(customBytes);
        check(custom.getMergedNotes()==1&&custom.getCustomNoteCount()==1&&custom.getCustomNoteCount(0)==1,"Custom warnings count retained notes after merging");
        int[][] dense=new int[200][5];for(int i=0;i<dense.length;i++)dense[i]=new int[]{0,i,20,45,i};
        NbsSong capped=parse(nbs("Dense",List.of("kick.ogg"),dense));
        check(capped.getCustomNoteCount()==128&&capped.getSkippedDenseNotes()==72,"Custom counts respect the parser density cap");
        check(OggTools.isVorbis(ogg()),"Synthetic sample has a Vorbis header");
        check(!OggTools.isVorbis(new byte[]{'O','g','g','S'}),"Ogg magic alone is not accepted as Vorbis");
        check(Math.abs(OggTools.readDurationSeconds(OggTools.shiftOctaves(ogg(),1))-0.5)<1e-9,"Shifted duration halves at an octave up");

        Player alice=new Player("Alice"),bob=new Player("Bob");Bukkit.getServer().players.addAll(List.of(alice,bob));
        NoteblockWorldMCPlugin plugin=new NoteblockWorldMCPlugin();plugin.onEnable();drain(plugin);
        try {
            var playback=plugin.getPlaybackManager();var downloads=plugin.getDownloadManager();var config=plugin.getConfig();var pack=plugin.getResourcePackService();
            NbsSong a=parse(nbs("A",List.of(),new int[][]{{0,0,0,45,100}}));
            NbsSong b=parse(nbs("B",List.of(),new int[][]{{0,0,0,45,100}}));
            NbsSong c=parse(nbs("C",List.of(),new int[][]{{0,0,0,45,100}}));
            playback.startBroadcast(alice,a);int beforeCuts=alice.cuts;
            playback.startBroadcast(alice,b);playback.startBroadcast(alice,c);
            check(playback.activeSongs().get(0).title().equals("A")&&playback.activeSongs().get(0).fading(),"Broadcast replacement keeps outgoing session during fade");
            check(alice.cuts==beforeCuts,"Starting a broadcast fade does not cut ringing audio");
            finishFade(playback);
            check(playback.activeSongs().get(0).title().equals("C"),"Only the newest queued broadcast starts after the fade");
            check(alice.cuts==beforeCuts,"Completed broadcast fade preserves its tail");
            playback.startBroadcast(alice,b);check(playback.stopBroadcastFading(),"Stop during a switch is handled");
            check(playback.activeSongs().isEmpty()&&field(playback,"queuedBroadcastStart")==null,"Stop cancels queued replacement");
            playback.startBroadcast(alice,a);playback.startBroadcast(alice,b);
            Object stale=field(playback,"broadcastSession");setField(stale,"fadeStartNanos",System.nanoTime()-2_000_000_000L);call(stale,"step");
            playback.stopBroadcast();playback.startBroadcast(alice,c);drain(plugin);
            check(playback.activeSongs().get(0).title().equals("C"),"Stale completion cannot replace a newer broadcast");
            playback.stopBroadcast();config.set("playback.fade-out-ms",0);playback.loadHotSettings();
            playback.startBroadcast(alice,a);Object immediate=field(playback,"broadcastSession");
            call(immediate,"emitNote",0,1f,field(immediate,"listeners"));beforeCuts=alice.cuts;playback.startBroadcast(alice,b);
            check(playback.activeSongs().get(0).title().equals("B")&&alice.cuts==beforeCuts+1,"Zero fade keeps immediate switching");
            playback.stopBroadcast();config.set("playback.fade-out-ms",500);playback.loadHotSettings();

            String id=downloads.importBytes(customBytes,"custom.nbs");
            NbsSong mixedSong=parse(nbs("Mixed",List.of("drums/kick.ogg"),new int[][]{{0,0,0,45,100},{0,1,20,45,100}}));
            playback.start(alice,mixedSong,id);
            check(warnings(alice,"playback.custom-audio-no-pack")==1,"Custom notes warn the listener when the pack is unavailable");
            Object personal=((Map<?,?>)field(playback,"sessions")).get(alice.getUniqueId());
            int emittedBefore=alice.gains.size(); call(personal,"emitChord",0,2,field(personal,"listeners"));
            check(alice.gains.size()==emittedBefore+1,"Missing custom sounds do not emit or consume voices");
            playback.stop(alice.getUniqueId());
            Path sounds=downloads.getSongDirectory(id).resolve("sounds");Files.createDirectories(sounds.resolve("drums"));
            Path sample=Files.write(sounds.resolve("drums/kick.ogg"),ogg());
            check(CustomSoundPaths.resolve(sounds,"drums\\kick.ogg").equals(sample.toRealPath()),"Nested Windows-style custom paths resolve correctly");
            check(CustomSoundPaths.resolve(sounds,"./drums/kick.ogg").equals(sample.toRealPath()),"Relative path prefixes are normalized");
            check(CustomSoundPaths.resolve(sounds,"../song.nbs")==null,"Custom paths cannot escape sounds directory");
            Path outside=Files.write(data.resolve("outside.ogg"),ogg());Files.createSymbolicLink(sounds.resolve("outside.ogg"),outside);
            check(CustomSoundPaths.resolve(sounds,"outside.ogg")==null,"Custom path resolution rejects escaping symlinks");Files.delete(sounds.resolve("outside.ogg"));
            check(!PolyphonyAnalyzer.analyze(plugin,custom,id).estimated(),"Nested custom sample duration is measured");
            Files.createDirectories(data.resolve("vanilla-sounds"));Files.write(data.resolve("vanilla-sounds/harp.ogg"),ogg());
            NbsSong fastPitch=parse(nbs("Pitch",List.of(),new int[][]{{0,0,0,57,100},{6,0,0,57,100}}));
            check(PolyphonyAnalyzer.analyze(plugin,fastPitch,null).peakVoices()==1,"Analyzer includes runtime pitch when calculating overlap");

            var oldApi=plugin.getApiClient();config.set("api-base-url","https://example.test/v1");config.set("http.connect-timeout",19);config.set("http.request-timeout",31);
            config.set("download.max-zip-size-mb",2);config.set("download.max-extracted-size-mb",3);config.set("download.max-stored-songs",1);config.set("download.max-concurrent-downloads",7);
            plugin.applySettings(config,true);drain(plugin);
            check(plugin.getApiClient()!=oldApi&&oldApi.retired,"Reload replaces and retires a changed API client");
            check(plugin.getApiClient().connect.getSeconds()==19&&plugin.getApiClient().request.getSeconds()==31,"Reload applies both HTTP timeouts");
            check(plugin.getDownloadManager()==downloads&&downloads.isDownloaded(id),"Reload retains the existing library manager and songs");
            check((long)field(downloads,"maxZipBytes")==2L*1024*1024&&(long)field(downloads,"maxExtractedBytes")==3L*1024*1024,"Reload applies both size caps");
            check((int)field(downloads,"maxConcurrentDownloads")==7,"Reload applies concurrent download limit");
            try{downloads.download("new-song",null).join();throw new AssertionError("Storage cap ignored");}catch(java.util.concurrent.CompletionException e){check(e.getCause().getMessage().contains("storage limit"),"Reloaded storage limit is enforced");}
            var sameApi=plugin.getApiClient();plugin.applySettings(config,false);check(plugin.getApiClient()==sameApi,"Unchanged API settings do not churn the client");

            int port=freePort();config.set("resource-pack.enabled",true);config.set("resource-pack.bind-address","127.0.0.1");config.set("resource-pack.port",port);
            config.set("resource-pack.public-url","http://127.0.0.1:"+port+"/pack.zip");config.set("resource-pack.pack-format",89);
            plugin.applySettings(config,true);drain(plugin);
            check(pack.isActive(),"Reload starts pack hosting");
            String event=ResourcePackService.customSoundEvent(id,"drums/kick.ogg");check(pack.hasEvent(event),"Pack includes nested custom audio");
            try(var zip=new ZipFile(data.resolve("resource-pack.zip").toFile())){
                String meta=new String(zip.getInputStream(zip.getEntry("pack.mcmeta")).readAllBytes(),StandardCharsets.UTF_8);
                check(meta.contains("\"min_format\":89")&&meta.contains("\"max_format\":89"),"Reload rebuilds pack format metadata");
            }
            UUID oldRequest=alice.packs.get(alice.packs.size()-1).info.id();config.set("resource-pack.pack-format",90);pack.loadHotSettings();drain(plugin);pack.send(alice);
            pack.onPackStatus(new PlayerResourcePackStatusEvent(alice,oldRequest,SUCCESSFULLY_LOADED));
            check(!pack.isPackLoaded(alice.getUniqueId()),"Old pack acknowledgements are ignored");
            pack.onPackStatus(new PlayerResourcePackStatusEvent(alice,UUID.randomUUID(),SUCCESSFULLY_LOADED));
            check(!pack.isResolved(alice.getUniqueId()),"Other plugins' pack acknowledgements are ignored");status(pack,alice,SUCCESSFULLY_LOADED);
            check(pack.isPackLoaded(alice.getUniqueId()),"Current pack acknowledgement unlocks custom audio");
            int pushes=alice.packs.size();pack.send(alice);check(pack.isResolved(alice.getUniqueId())&&alice.packs.size()==pushes,"Unchanged generation does not reload a settled client");
            check(!alice.packs.get(alice.packs.size()-1).required,"Mid-session re-pushes are optional");
            config.set("resource-pack.kick-on-refuse",false);pack.loadHotSettings();pack.onQuit(new org.bukkit.event.player.PlayerQuitEvent(alice));pack.onJoin(new PlayerJoinEvent(alice));
            check(!alice.packs.get(alice.packs.size()-1).required,"kick-on-refuse false also makes initial pack optional");status(pack,alice,DECLINED);check(alice.kicks==0,"Optional initial refusal does not kick");
            config.set("resource-pack.kick-on-refuse",true);pack.loadHotSettings();pack.onQuit(new org.bukkit.event.player.PlayerQuitEvent(bob));pack.onJoin(new PlayerJoinEvent(bob));
            check(bob.packs.get(bob.packs.size()-1).required,"Enforced initial pack remains required");status(pack,bob,FAILED_DOWNLOAD);check(bob.kicks==1,"Enforced initial failure kicks");

            int newPort=freePort();config.set("resource-pack.port",newPort);config.set("resource-pack.public-url","http://127.0.0.1:"+newPort+"/pack.zip");plugin.applySettings(config,false);
            check(((java.net.InetSocketAddress)call(field(pack,"httpServer"),"address")).getPort()==newPort,"Reload rebinds the pack HTTP port");
            config.set("resource-pack.max-custom-sounds-mb",0);config.set("resource-pack.pack-format",90);plugin.applySettings(config,true);drain(plugin);
            check(!pack.hasEvent(event),"Reloaded audio budget removes unavailable events");status(pack,alice,SUCCESSFULLY_LOADED);status(pack,bob,DECLINED);
            alice.messages.clear();bob.messages.clear();playback.startBroadcast(alice,custom,id);
            check(warnings(alice,"playback.custom-audio-missing")==1,"Loaded-pack listener is warned about missing samples");
            check(warnings(bob,"playback.custom-audio-no-pack")==1,"Broadcast warnings are listener-specific");
            check(alice.messages.stream().anyMatch(m->m.value.contains("count=1")&&m.value.contains("drums/kick.ogg")),"Warning includes retained count and missing filename");
            Bukkit.getScheduler().tick();Bukkit.getScheduler().tick();check(warnings(alice,"playback.custom-audio-missing")==1,"Warnings do not repeat every tick");
            config.set("resource-pack.max-custom-sounds-mb",64);plugin.applySettings(config,false);drain(plugin);status(pack,alice,SUCCESSFULLY_LOADED);status(pack,bob,SUCCESSFULLY_LOADED);
            Object broadcast=field(playback,"broadcastSession");call(broadcast,"refreshListeners");int emitted=alice.gains.size();
            call(broadcast,"emitNote",0,1f,field(broadcast,"listeners"));
            check(alice.gains.size()==emitted+1,"Restored custom audio emits after the refreshed pack loads");
            playback.stopBroadcast();config.set("resource-pack.pack-format",91);pack.loadHotSettings();drain(plugin);pack.send(alice);playback.startBroadcast(alice,a);
            check(field(playback,"pendingBroadcast")!=null,"Broadcast waits for unresolved packs");check(playback.stopBroadcastFading(),"Stop cancels a pack-waiting broadcast");
            status(pack,alice,SUCCESSFULLY_LOADED);Bukkit.getScheduler().tick();check(playback.activeSongs().isEmpty(),"Cancelled pack wait cannot start playback later");
            config.set("resource-pack.enabled",false);plugin.applySettings(config,false);check(!pack.isActive(),"Reload stops pack hosting");

            Path edited=data.resolve("config-test.yml");Files.writeString(edited,"# header\nplayback:\n  # Fade docs\n  fade-out-ms: 500\n  humanize: true\n");
            ConfigEditor.write(edited,ConfigEditor.find("fade-out-ms"),"750");
            check(Files.readString(edited).contains("# Fade docs\n  fade-out-ms: 750\n  humanize: true"),"Config editing preserves surrounding comments/settings");
            System.out.println("PASS: "+checks+" regression checks (imports, fades, reload, packs, custom audio, analysis).");
        } finally { plugin.onDisable(); }
    }
}
