import io.github.noteblockworldmc.NoteblockWorldMCPlugin;
import io.github.noteblockworldmc.nbs.*;
import io.github.noteblockworldmc.pack.*;
import io.github.noteblockworldmc.playback.*;
import io.github.noteblockworldmc.gui.*;
import io.github.noteblockworldmc.api.NoteBlockWorldClient.SongQuery;
import io.github.noteblockworldmc.api.model.*;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.*;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.*;
import static org.bukkit.event.player.PlayerResourcePackStatusEvent.Status.*;

/** Behavioral checks for the source-audit findings. All network traffic is loopback. */
public class FixChecks extends RegressionChecks {
    static Object personal(PlaybackManager manager, Player player)throws Exception {
        return ((Map<?,?>)field(manager,"sessions")).get(player.getUniqueId());
    }
    static String hash(byte[] bytes)throws Exception {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(bytes));}
    static byte[] zip(byte[] nbs, boolean directoryPayload)throws Exception {
        var out=new ByteArrayOutputStream();try(var zip=new ZipOutputStream(out)){
            if(directoryPayload){zip.putNextEntry(new ZipEntry("hidden/"));zip.write(new byte[2*1024*1024]);zip.closeEntry();}
            zip.putNextEntry(new ZipEntry("song.nbs"));zip.write(nbs);zip.closeEntry();
        }return out.toByteArray();
    }
    static byte[] loop(byte[] source)throws Exception {
        byte[] data=source.clone();try(var in=new DataInputStream(new ByteArrayInputStream(data))){
            in.skipNBytes(8);for(int i=0;i<4;i++)in.skipNBytes(Integer.reverseBytes(in.readInt()));
            in.skipNBytes(25);in.skipNBytes(Integer.reverseBytes(in.readInt()));int at=data.length-in.available();data[at]=1;data[at+1]=1;
        }return data;
    }
    static void rejected(Throwing action,String message)throws Exception {
        try{action.run();throw new AssertionError(message);}catch(Exception expected){Throwable root=expected;while(root.getCause()!=null)root=root.getCause();
            if(!(root instanceof IOException||root instanceof IllegalStateException||root instanceof IllegalArgumentException))throw expected;
            check(true,message);}
    }
    interface Throwing {void run()throws Exception;}
    public static void main(String[] args)throws Exception {
        Path data=Path.of(args[0]);Files.createDirectories(data);System.setProperty("nbw.test.data",data.toString());
        Player alice=new Player("Alice"),bob=new Player("Bob");Bukkit.getServer().players.addAll(List.of(alice,bob));
        var plugin=new NoteblockWorldMCPlugin();plugin.onEnable();drain(plugin);
        try {
            var downloads=plugin.getDownloadManager();var playback=plugin.getPlaybackManager();var pack=plugin.getResourcePackService();var config=plugin.getConfig();
            byte[] aBytes=nbs("A",List.of(),new int[][]{{0,0,0,45,100},{20,0,0,45,100}});
            NbsSong a=parse(aBytes),b=parse(nbs("B",List.of(),new int[][]{{0,0,0,45,100}})),c=parse(nbs("C",List.of(),new int[][]{{0,0,0,45,100}}));
            String aId=downloads.importBytes(aBytes,"A.nbs");
            playback.playDownloaded(alice,aId);check(playback.stopFading(alice.getUniqueId()),"Stop recognizes a pending cold parse");drain(plugin);
            check(personal(playback,alice)==null,"A08: cold parse cannot start after stop");
            var old=playback.beginPlay(alice,alice,false);var latest=playback.beginPlay(alice,alice,false);
            check(!playback.isCurrent(old)&&playback.isCurrent(latest),"A08: a newer selection invalidates the older download continuation");
            playback.stop(alice.getUniqueId());check(!playback.isCurrent(latest),"A08: stop invalidates the entire play request");
            bob.denied.add("noteblockworldmc.play");var target=playback.beginPlay(alice,bob,false);playback.playDownloaded(target,alice,aId);drain(plugin);
            check(personal(playback,bob)!=null,"An authorized caller can play to a target without requiring the target's command permission");
            playback.stop(bob.getUniqueId());bob.denied.clear();
            var revoked=playback.beginPlay(alice,alice,false);alice.denied.add("noteblockworldmc.play");
            check(!playback.isCurrent(revoked),"Pending playback rechecks caller permissions");alice.denied.clear();playback.stop(alice.getUniqueId());

            playback.start(alice,a);Object stale=personal(playback,alice);call(stale,"requestFinish");playback.start(alice,b);Object newer=personal(playback,alice);playback.start(alice,c);
            drain(plugin);check(personal(playback,alice)==newer&&(boolean)call(newer,"isFading"),"A09: stale personal completion cannot consume the next session's queue");
            setField(newer,"fadeStartNanos",System.nanoTime()-2_000_000_000L);call(newer,"step");drain(plugin);
            check(field(personal(playback,alice),"song")==c,"A09: queued replacement starts when its own outgoing fade finishes");playback.stop(alice.getUniqueId());

            @SuppressWarnings("unchecked") Set<String> inFlight=(Set<String>)field(downloads,"inFlight");inFlight.add("catalog-pending");
            config.set("download.max-stored-songs",downloads.getDownloadedIds().size()+1);downloads.loadHotSettings();
            rejected(()->downloads.importBytes(aBytes,"extra.nbs"),"A11: imports count catalog reservations in the storage limit");
            inFlight.clear();config.set("download.max-stored-songs",500);config.set("download.max-extracted-size-mb",1);downloads.loadHotSettings();
            rejected(()->call(downloads,"extract","directory-payload",zip(aBytes,true),null),"A04: data-bearing ZIP directories are rejected");
            check(!Files.exists(downloads.getNbsFile("directory-payload")),"Failed ZIP extraction never publishes a song");
            Path source=Files.write(downloads.getImportDirectory().resolve("once.nbs"),aBytes);
            var imports=Executors.newFixedThreadPool(2);try {
                Callable<Boolean> action=()->{try{downloads.importLocal(source);return true;}catch(Exception expected){return false;}};
                var one=imports.submit(action);var two=imports.submit(action);
                check(one.get(3,TimeUnit.SECONDS)^two.get(3,TimeUnit.SECONDS),"A11: concurrent bulk imports consume a source exactly once");
                check(!Files.exists(source),"Successful local imports consume their claimed source");
            }finally{imports.shutdownNow();}
            Path bad=Files.write(downloads.getImportDirectory().resolve("invalid.nbs"),new byte[]{1});
            rejected(()->downloads.importLocal(bad),"Invalid local NBS fails cleanly");check(Files.exists(bad),"Failed imports restore the source filename");

            byte[] base=ogg(),distinct=OggTools.shiftOctaves(base,1);Path sounds=downloads.getSongDirectory(aId).resolve("sounds");Files.createDirectories(sounds);
            Files.write(sounds.resolve("tone.ogg"),base);Files.write(sounds.resolve("tone_m1.ogg"),distinct);pack.rebuild();
            String tone=ResourcePackService.customSoundEvent(aId,"tone.ogg"),named=ResourcePackService.customSoundEvent(aId,"tone_m1.ogg");
            check(!named.equals(tone+"_m1")&&pack.hasEvent(tone)&&pack.hasEvent(named),"A12: source names cannot collide with octave variants");
            try(var zip=new ZipFile(data.resolve("resource-pack.zip").toFile())){
                check(Arrays.equals(zip.getInputStream(zip.getEntry("assets/nbw/sounds/custom/"+HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(distinct))+".ogg")).readAllBytes(),distinct),"A12: a variant-looking filename retains its own audio bytes");
            }
            String firstHash=pack.getPackSha1();pack.rebuild();check(firstHash.equals(pack.getPackSha1()),"Identical pack content has deterministic bytes and hash");
            setField(pack,"maxCustomSoundBytes",2L*base.length);pack.rebuild();
            check(pack.hasEvent(tone)&&pack.hasEvent(named)&&!pack.hasEvent(tone+"_m1"),"All fitting base samples take priority over optional variants");
            Path outside=Files.write(data.resolve("outside.ogg"),base);Files.createSymbolicLink(sounds.resolve("escape.ogg"),outside);pack.rebuild();
            check(!pack.hasEvent(ResourcePackService.customSoundEvent(aId,"escape.ogg")),"External audio symlinks are excluded from packs");
            setField(pack,"maxCustomSoundBytes",64L*1024*1024);pack.rebuild();
            byte[] corrupt=base.clone();corrupt[corrupt.length-1]^=1;
            check(!OggTools.isVorbis(corrupt)&&!OggTools.isVorbis(Arrays.copyOf(base,base.length-1)),"CRC-damaged and truncated Ogg streams are rejected");

            // A hot reload retains the download manager, so deleting files
            // behind the server must explicitly rebuild every local index.
            String staleId=downloads.importBytes(aBytes,"stale.nbs");
            Path staleDir=downloads.getSongDirectory(staleId),staleSounds=staleDir.resolve("sounds");
            Files.createDirectories(staleSounds);Files.write(staleSounds.resolve("stale.ogg"),base);
            String staleEvent=ResourcePackService.customSoundEvent(staleId,"stale.ogg");pack.rebuild();
            check(pack.hasEvent(staleEvent),"Reload-reconciliation fixture is present in the resource pack");
            var refreshed=downloads.reconcileLibrary();
            check(refreshed.successful()&&refreshed.added().isEmpty()&&refreshed.removed().isEmpty(),
                    "Reload reconciliation rechecks intact songs and clears local-entry caches");
            Files.delete(staleDir.resolve("song.nbs"));
            var removedLibrary=downloads.reconcileLibrary();
            check(removedLibrary.removed().contains(staleId)&&!downloads.isDownloaded(staleId)
                    &&!downloads.getDownloadedIds().contains(staleId)&&downloads.resolve(staleId).isEmpty(),
                    "Reload reconciliation removes songs whose files disappeared");
            pack.rebuild();check(!pack.hasEvent(staleEvent),"Removed songs no longer contribute custom instruments to the pack");

            NbsSong loopSong=parse(loop(aBytes));playback.start(alice,loopSong);Object loopSession=personal(playback,alice);
            setField(loopSession,"elapsedSeconds",loopSong.getDurationSeconds()-.09);setField(loopSession,"nextChord",loopSong.firstChordAfter(19));setField(loopSession,"lastStepNanos",System.nanoTime()-1_000_000L);call(loopSession,"step");
            check((int)field(loopSession,"loopsDone")==0,"A10: the final tick retains its complete time interval");
            setField(loopSession,"elapsedSeconds",loopSong.getDurationSeconds()-.01);setField(loopSession,"lastStepNanos",System.nanoTime()-20_000_000L);call(loopSession,"step");
            check((int)field(loopSession,"loopsDone")==1&&(double)field(loopSession,"elapsedSeconds")>=.009&&(double)field(loopSession,"elapsedSeconds")<.04,"A10: loop restart carries fractional elapsed time");playback.stop(alice.getUniqueId());
            var ctor=NbsSong.class.getDeclaredConstructors()[0];ctor.setAccessible(true);
            NbsSong tempos=(NbsSong)ctor.newInstance("tempo","",10.0,30,Map.of(0,List.of(new PlayableNote(0,1,1,0,-1,0))),0,0,0,List.of(),new TreeMap<>(Map.of(10,20.0,20,5.0)),false,0,0);
            check(Math.abs(tempos.getDurationSeconds()-3.7)<1e-9&&Math.abs(tempos.positionAtSeconds(1.6)-20.5)<1e-9,"Tempo integration and its inverse agree across multiple changes");

            int port=freePort();config.set("resource-pack.enabled",true);config.set("resource-pack.bind-address","127.0.0.1");config.set("resource-pack.port",port);config.set("resource-pack.public-url","http://127.0.0.1:"+port+"/pack.zip");pack.loadHotSettings();drain(plugin);pack.send(alice);
            var advertised=alice.packs.get(alice.packs.size()-1).info;status(pack,alice,SUCCESSFULLY_LOADED);
            Files.write(sounds.resolve("tone.ogg"),distinct);pack.rebuild();
            var received=HttpClient.newHttpClient().send(HttpRequest.newBuilder(advertised.uri()).timeout(Duration.ofSeconds(2)).build(),HttpResponse.BodyHandlers.ofByteArray());
            check(received.statusCode()==200&&hash(received.body()).equals(advertised.hash()),"A05: advertised URL continues serving its exact generation after rebuild");
            pack.send(alice);status(pack,alice,SUCCESSFULLY_LOADED);
            check(alice.packs.stream().noneMatch(r->r.replace)&&alice.removedPacks.contains(advertised.id()),"A06: NBW removes only its previous pack and never replaces unrelated packs");
            UUID foreign=UUID.randomUUID();pack.onPackStatus(new PlayerResourcePackStatusEvent(alice,foreign,FAILED_DOWNLOAD));
            check(pack.isPackLoaded(alice.getUniqueId())&&!alice.removedPacks.contains(foreign),"Foreign resource-pack IDs do not alter NBW state");
            String extra=downloads.importBytes(aBytes,"other.nbs");Path other=downloads.getSongDirectory(extra).resolve("sounds");Files.createDirectories(other);Files.write(other.resolve("other.ogg"),base);
            String otherEvent=ResourcePackService.customSoundEvent(extra,"other.ogg");setField(pack,"maxCustomSoundBytes",(long)base.length);pack.rebuild();pack.send(alice);status(pack,alice,SUCCESSFULLY_LOADED);
            boolean includesOther=pack.hasEvent(otherEvent);String removed=includesOther?extra:aId;int pushes=alice.packs.size();
            check(downloads.remove(removed),"Removal publishes the completed library change");drain(plugin);
            String newlyAvailable=includesOther?tone:otherEvent;
            check(pack.hasEvent(newlyAvailable)&&!pack.hasEvent(alice.getUniqueId(),newlyAvailable)&&alice.packs.size()==pushes,"A07: quiet rebuild does not claim new events are installed for existing clients");
            check(pack.isPackLoaded(alice.getUniqueId()),"Quiet rebuild retains the acknowledged old manifest");

            var proposal=new org.bukkit.configuration.file.FileConfiguration();proposal.values.putAll(config.values);proposal.set("playback.humanize-velocity",Double.NaN);
            var beforeApi=plugin.getApiClient();rejected(()->plugin.applySettings(proposal,false),"Nonfinite configuration values are rejected before commit");
            check(plugin.getApiClient()==beforeApi&&plugin.getConfig()==config,"Rejected reload retains the previous runtime configuration");
            try(var occupied=new ServerSocket(0,1,InetAddress.getLoopbackAddress())) {
                proposal.values.clear();proposal.values.putAll(config.values);proposal.set("resource-pack.port",occupied.getLocalPort());proposal.set("resource-pack.max-custom-sounds-mb",0);proposal.set("resource-pack.pack-format",99);proposal.set("api-base-url","https://example.test/v1");
                int oldFormat=(int)field(pack,"configuredPackFormat");long oldBudget=(long)field(pack,"maxCustomSoundBytes");
                rejected(()->plugin.applySettings(proposal,false),"A failed replacement bind aborts the whole reload");
                check(pack.isActive()&&(int)field(pack,"configuredPackFormat")==oldFormat&&(long)field(pack,"maxCustomSoundBytes")==oldBudget&&plugin.getApiClient()==beforeApi,"Bind failure retains content settings, endpoint and API client together");
            }

            String remaining=downloads.getDownloadedIds().iterator().next();var gui=new SongBrowserGui(plugin,alice,SongQuery.builder().downloadedOnly(true).build());alice.openInventory(gui.getInventory());
            setField(gui,"currentPage",SongPage.of(List.of(SongPreview.local(remaining,"visible","",1,1,0,"")),1,36,1));call(gui,"render");
            call(gui,"renderLoading");gui.handleClick(0,ClickType.SHIFT_RIGHT);gui.handleClick(0,ClickType.LEFT);
            check(downloads.isDownloaded(remaining)&&field(gui,"pendingRemoveId")==null&&personal(playback,alice)==null,"A13: hidden songs cannot be played or armed for deletion while loading");
            call(gui,"renderError");gui.handleClick(0,ClickType.SHIFT_RIGHT);check(field(gui,"pendingRemoveId")==null,"A13: error screens disable old song actions");
            call(gui,"render");var click=new InventoryClickEvent(bob,gui.getInventory(),gui.getInventory(),0,ClickType.SHIFT_RIGHT);new GuiListener().onInventoryClick(click);
            check(click.cancelled&&field(gui,"pendingRemoveId")==null,"Only the inventory's owner can operate its controls");

            config.set("resource-pack.enabled",false);pack.loadHotSettings();config.set("playback.voice-ceiling",16);config.set("playback.fade-out-ms",0);playback.loadHotSettings();
            playback.start(alice,a);Object ringing=personal(playback,alice);
            for(int i=0;i<16;i++)call(ringing,"emitNote",0,1f,field(ringing,"listeners"));
            int emitted=alice.gains.size();call(ringing,"requestFinish");drain(plugin);playback.start(alice,b);Object incoming=personal(playback,alice);
            call(incoming,"emitNote",0,1f,field(incoming,"listeners"));
            check(alice.gains.size()==emitted,"A16: a new session reserves room for the previous session's ringing tails");
            playback.startBroadcast(alice,b);Object broadcast=field(playback,"broadcastSession");int bobBefore=bob.gains.size();call(broadcast,"emitNote",0,1f,field(broadcast,"listeners"));
            check(alice.gains.size()==emitted&&bob.gains.size()==bobBefore+1,"A16: broadcast budgets are independent for each listener");playback.stopBroadcast();playback.stop(alice.getUniqueId());

            var lock=(java.util.concurrent.locks.ReentrantLock)field(playback,"driverLock");var held=new CountDownLatch(1);var release=new CountDownLatch(1);
            Thread blocker=new Thread(()->{lock.lock();try{held.countDown();try{release.await(2,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}}finally{lock.unlock();}});blocker.start();check(held.await(1,TimeUnit.SECONDS),"Clock-handover test owns the old driver lock");
            playback.start(alice,a);Object timing=personal(playback,alice);config.set("playback.high-precision",true);playback.loadHotSettings();config.set("playback.high-precision",false);playback.loadHotSettings();call(timing,"mainTick");
            check((double)field(timing,"position")==0,"A14: clock reload never overlaps a driver still holding the session lock");release.countDown();blocker.join(2000);call(timing,"mainTick");setField(timing,"lastStepNanos",System.nanoTime()-20_000_000L);call(timing,"mainTick");
            check((double)field(timing,"position")>0,"Playback resumes after exclusive clock handover");playback.stop(alice.getUniqueId());
            Files.createDirectories(data.resolve("vanilla-sounds"));Files.write(data.resolve("vanilla-sounds/harp.ogg"),base);pack.reloadContent();drain(plugin);
            NbsSong tie=parse(nbs("ties",List.of(),new int[][]{{0,0,0,45,100},{10,0,0,45,100}}));var report=PolyphonyAnalyzer.analyze(plugin,tie,null);
            check(report.peakVoices()==1,"A15: a sound ending at the next onset does not inflate peak overlap");
            check(report.meanVoices()<=report.peakVoices()&&report.observationSeconds()>=2,"A15: mean and density use the full observation period including tails");
            check(io.github.noteblockworldmc.util.Limits.display("x".repeat(10000)).length()<=257,"Untrusted display text is bounded");
        } finally {plugin.onDisable();}
        System.out.println("PASS: "+checks+" audit-fix checks.");
    }
}
