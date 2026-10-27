#!/usr/bin/env python3
"""Exercise actual plugin code with small Bukkit/HTTP test doubles (Java 17+).
This is a regression harness, not a replacement for a Java 25 / Paper build.
"""
from pathlib import Path
import os, shutil, subprocess, tempfile

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / 'src/main/java'
PREFIX = 'io/github/noteblockworldmc/'
STUBS = {}
def stub(name, body):
    STUBS[name.replace('.', '/') + '.java'] = 'package ' + name.rpartition('.')[0] + ';\n' + body

stub('org.bukkit.configuration.file.FileConfiguration', '''
import java.util.*;
public class FileConfiguration {
 public final Map<String,Object> values = new HashMap<>();
 public FileConfiguration() { set("playback.high-precision",false); set("playback.humanize",false);
  set("playback.show-actionbar",false); set("resource-pack.download-vanilla-sounds",false);
  set("resource-pack.settle-delay-ms",0); }
 public Object get(String k){return values.get(k);} public Map<String,Object> getValues(boolean deep){return Map.copyOf(values);}
 public void set(String k,Object v){values.put(k,v);}
 public boolean getBoolean(String k){return getBoolean(k,false);} public long getLong(String k){return getLong(k,0);}public double getDouble(String k){return getDouble(k,0);}
 public boolean getBoolean(String k,boolean d){return values.getOrDefault(k,d) instanceof Boolean b?b:d;}
 public int getInt(String k,int d){return ((Number)values.getOrDefault(k,d)).intValue();}
 public long getLong(String k,long d){return ((Number)values.getOrDefault(k,d)).longValue();}
 public double getDouble(String k,double d){return ((Number)values.getOrDefault(k,d)).doubleValue();}
 public String getString(String k,String d){return String.valueOf(values.getOrDefault(k,d));}
}
''')
stub('org.bukkit.configuration.file.YamlConfiguration', """
public class YamlConfiguration extends FileConfiguration {
 public static volatile java.util.function.Consumer<FileConfiguration> onLoad=c->{};
 public void load(java.io.File file) throws java.io.IOException {onLoad.accept(this);} public void setDefaults(YamlConfiguration c){}
 public static YamlConfiguration loadConfiguration(java.io.Reader r){return new YamlConfiguration();}
}
""")
stub('org.bukkit.scheduler.BukkitTask','''
public class BukkitTask { public final Runnable action; public boolean cancelled;
 public BukkitTask(Runnable r){action=r;} public void cancel(){cancelled=true;} }
''')
stub('org.bukkit.scheduler.BukkitScheduler','''
import java.util.*;
public class BukkitScheduler {
 public final Deque<BukkitTask> queued=new java.util.concurrent.ConcurrentLinkedDeque<>(); public final List<BukkitTask> timers=new ArrayList<>();
 public BukkitTask runTask(Object p,Runnable r){var t=new BukkitTask(r);queued.add(t);return t;}
 public BukkitTask runTaskAsynchronously(Object p,Runnable r){return runTask(p,r);}
 public BukkitTask runTaskLater(Object p,Runnable r,long d){return runTask(p,r);}
 public BukkitTask runTaskTimer(Object p,Runnable r,long d,long every){var t=new BukkitTask(r);timers.add(t);return t;}
 public void drain(){int guard=0;while(!queued.isEmpty()){if(++guard>1000)throw new AssertionError("Task loop");var t=queued.remove();if(!t.cancelled)t.action.run();}}
 public void tick(){for(var t:List.copyOf(timers))if(!t.cancelled)t.action.run();drain();}
}
''')
stub('org.bukkit.plugin.PluginManager','public class PluginManager { public void registerEvents(Object listener,Object plugin){} }')
stub('org.bukkit.Server','''
import java.util.*; import org.bukkit.entity.Player; import net.kyori.adventure.text.Component;
public class Server {
 public final List<Player> players=new ArrayList<>(); public final List<Component> messages=new ArrayList<>();
 public Collection<Player> getOnlinePlayers(){return players;}
 public Player getPlayer(UUID id){return players.stream().filter(p->p.getUniqueId().equals(id)).findFirst().orElse(null);}
 public void sendMessage(Component c){messages.add(c);} public org.bukkit.plugin.PluginManager getPluginManager(){return new org.bukkit.plugin.PluginManager();}
 public org.bukkit.scheduler.BukkitScheduler getScheduler(){return Bukkit.getScheduler();}
}
''')
stub('org.bukkit.Bukkit','''
public class Bukkit {
 private static final Server server=new Server(); private static final org.bukkit.scheduler.BukkitScheduler scheduler=new org.bukkit.scheduler.BukkitScheduler();
 public static Server getServer(){return server;} public static org.bukkit.scheduler.BukkitScheduler getScheduler(){return scheduler;}
 public static java.util.Collection<org.bukkit.entity.Player> getOnlinePlayers(){return server.getOnlinePlayers();}
}
''')
stub('org.bukkit.plugin.java.JavaPlugin','''
public class JavaPlugin {
 private final java.io.File directory=new java.io.File(System.getProperty("nbw.test.data"));
 private final org.bukkit.configuration.file.FileConfiguration config=new org.bukkit.configuration.file.FileConfiguration();
 public void onEnable(){} public void onDisable(){} public void saveDefaultConfig(){} public void reloadConfig(){} public java.io.InputStream getResource(String name){return null;}
 public org.bukkit.configuration.file.FileConfiguration getConfig(){return config;}
 public java.io.File getDataFolder(){return directory;} public java.util.logging.Logger getLogger(){return java.util.logging.Logger.getLogger("regression");}
 public org.bukkit.Server getServer(){return org.bukkit.Bukkit.getServer();} public boolean isEnabled(){return true;}
 public Meta getPluginMeta(){return new Meta();} public record Meta(){public String getVersion(){return "test";}}
}
''')
stub('org.bukkit.event.Listener','public interface Listener {}')
stub('org.bukkit.event.EventHandler','public @interface EventHandler {}')
stub('org.bukkit.World','public class World {}')
stub('org.bukkit.SoundCategory','public enum SoundCategory { RECORDS, MASTER }')
sounds = ['HARP','BASS','BASEDRUM','SNARE','HAT','GUITAR','FLUTE','BELL','CHIME','XYLOPHONE','IRON_XYLOPHONE','COW_BELL','DIDGERIDOO','BIT','BANJO','PLING','TRUMPET','TRUMPET_EXPOSED','TRUMPET_WEATHERED','TRUMPET_OXIDIZED']
stub('org.bukkit.Sound','public enum Sound { '+','.join('BLOCK_NOTE_BLOCK_'+s for s in sounds)+' }')
stub('org.bukkit.util.Vector','''
public class Vector {private final double x,y,z;public Vector(double x,double y,double z){this.x=x;this.y=y;this.z=z;}
 public double getX(){return x;}public double getY(){return y;}public double getZ(){return z;}}
''')
stub('org.bukkit.Location','''
public class Location {private World w;private double x,y,z;private float yaw,pitch;public Location(World w,double x,double y,double z){this.w=w;this.x=x;this.y=y;this.z=z;}
 public Location(World w,double x,double y,double z,float yaw,float pitch){this(w,x,y,z);this.yaw=yaw;this.pitch=pitch;}public float getPitch(){return pitch;}
 public World getWorld(){return w;}public void setWorld(World w){this.w=w;}public double getX(){return x;}public double getY(){return y;}public double getZ(){return z;}
 public void setX(double x){this.x=x;}public void setY(double y){this.y=y;}public void setZ(double z){this.z=z;}public float getYaw(){return yaw;}}
''')
stub('net.kyori.adventure.text.Component','''
public class Component {public final String value;public Component(String v){value=v;}public static Component text(String v){return new Component(v);}
 public static Component empty(){return text("");}public static Component newline(){return text("\\n");}public Component append(Component c){return text(value+c.value);}public String toString(){return value;}}
''')
stub('net.kyori.adventure.text.minimessage.tag.resolver.TagResolver','public record TagResolver(String key,Object value) {}')
stub('net.kyori.adventure.text.minimessage.tag.resolver.Placeholder','''
public class Placeholder {public static TagResolver unparsed(String k,String v){return new TagResolver(k,v);}
 public static TagResolver component(String k,net.kyori.adventure.text.Component c){return new TagResolver(k,c);}}
''')
stub('net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer','''
public class PlainTextComponentSerializer {public static PlainTextComponentSerializer plainText(){return new PlainTextComponentSerializer();}
 public String serialize(net.kyori.adventure.text.Component c){return c.value;}}
''')
stub('net.kyori.adventure.resource.ResourcePackInfo','''
public record ResourcePackInfo(java.util.UUID id,java.net.URI uri,String hash){
 public static ResourcePackInfo resourcePackInfo(java.util.UUID i,java.net.URI u,String h){return new ResourcePackInfo(i,u,h);}}
''')
stub('net.kyori.adventure.resource.ResourcePackRequest','''
public class ResourcePackRequest {public ResourcePackInfo info;public boolean required,replace;
 public static ResourcePackRequest resourcePackRequest(){return new ResourcePackRequest();}
 public ResourcePackRequest packs(ResourcePackInfo i){info=i;return this;}public ResourcePackRequest required(boolean r){required=r;return this;}
 public ResourcePackRequest prompt(net.kyori.adventure.text.Component c){return this;}public ResourcePackRequest replace(boolean b){replace=b;return this;}
 public ResourcePackRequest build(){return this;}}
''')
stub('org.bukkit.entity.Player','''
import java.util.*;import org.bukkit.*;import net.kyori.adventure.text.Component;import net.kyori.adventure.resource.ResourcePackRequest;
public class Player {
 private final UUID id=UUID.randomUUID();private final String name;public boolean online=true;public int cuts,kicks;
 public final List<Component> messages=new ArrayList<>();public final List<ResourcePackRequest> packs=new ArrayList<>();public final List<Float> gains=new ArrayList<>(),pitches=new ArrayList<>();
 public java.net.InetSocketAddress address;public java.net.InetSocketAddress getAddress(){return address;}
 public Player(String n){name=n;}public UUID getUniqueId(){return id;}public String getName(){return name;}public boolean isOnline(){return online;} public boolean hasPermission(String p){return true;}
 public World world=new World();public double x,y=1.62,z;public float yaw,pitch;public int ping;public final List<Location> soundAt=new ArrayList<>();
 public Location getEyeLocation(){return new Location(world,x,y,z,yaw,pitch);}public int getPing(){return ping;}public double getEyeHeight(){return 1.62;}
 public final List<Object> stoppedSounds=new ArrayList<>();public int broadCuts;
 public void sendMessage(Component c){messages.add(c);}public void sendActionBar(Component c){}public void stopSound(SoundCategory c){cuts++;broadCuts++;}
 public void stopSound(Sound s,SoundCategory c){cuts++;stoppedSounds.add(s);}public void stopSound(String s,SoundCategory c){cuts++;stoppedSounds.add(s);}
 public void playSound(Player p,String k,SoundCategory c,float v,float f){gains.add(v);pitches.add(f);}public void playSound(Player p,Sound k,SoundCategory c,float v,float f){gains.add(v);pitches.add(f);}
 public void playSound(Location p,String k,SoundCategory c,float v,float f){gains.add(v);pitches.add(f);}public void playSound(Location p,Sound k,SoundCategory c,float v,float f){gains.add(v);pitches.add(f);}
 public void playSound(Player p,String k,SoundCategory c,float v,float f,long seed){gains.add(v);pitches.add(f);}public void playSound(Player p,Sound k,SoundCategory c,float v,float f,long seed){gains.add(v);pitches.add(f);}
 public void playSound(Location p,String k,SoundCategory c,float v,float f,long seed){gains.add(v);pitches.add(f);soundAt.add(new Location(p.getWorld(),p.getX(),p.getY(),p.getZ()));}
 public void playSound(Location p,Sound k,SoundCategory c,float v,float f,long seed){gains.add(v);pitches.add(f);soundAt.add(new Location(p.getWorld(),p.getX(),p.getY(),p.getZ()));}
 public final List<UUID> removedPacks=new ArrayList<>();public final List<String> packOps=new ArrayList<>();
 public void removeResourcePacks(UUID id,UUID... others){removedPacks.add(id);packOps.add("pop:"+id);}
 public void sendResourcePacks(ResourcePackRequest r){packs.add(r);packOps.add("push:"+r.info.id());}public void kick(Component c){kicks++;}
}
''')
for name in ['PlayerJoinEvent','PlayerQuitEvent']:
    stub('org.bukkit.event.player.'+name, 'public record '+name+'(org.bukkit.entity.Player player){public org.bukkit.entity.Player getPlayer(){return player;}}')
stub('org.bukkit.event.player.PlayerResourcePackStatusEvent','''
public record PlayerResourcePackStatusEvent(org.bukkit.entity.Player player,java.util.UUID id,Status status){
 public enum Status {SUCCESSFULLY_LOADED,DECLINED,FAILED_DOWNLOAD,INVALID_URL,FAILED_RELOAD,DISCARDED,ACCEPTED,DOWNLOADED}
 public org.bukkit.entity.Player getPlayer(){return player;}public java.util.UUID getID(){return id;}public Status getStatus(){return status;}}
''')
stub('com.google.gson.annotations.SerializedName','public @interface SerializedName {String value();}')
stub('com.google.gson.reflect.TypeToken','public class TypeToken<T> {public java.lang.reflect.Type getType(){return Object.class;}}')
stub('com.google.gson.GsonBuilder','public class GsonBuilder {public GsonBuilder setPrettyPrinting(){return this;}public Gson create(){return new Gson();}}')
stub('com.google.gson.Gson',r'''
import java.util.*;
public class Gson {
 public static volatile java.util.function.Consumer<String> beforeRead=s->{};
 public static volatile java.util.function.Function<String,Object> readResult=s->null;
 public <T>T fromJson(String s,java.lang.reflect.Type t){beforeRead.accept(s);return (T)readResult.apply(s);}
 public String toJson(Object v){
  if(v==null)return "null";if(v instanceof Number||v instanceof Boolean)return v.toString();
  if(v instanceof Map<?,?> m){var items=new ArrayList<String>();for(var e:m.entrySet())items.add(toJson(e.getKey().toString())+":"+toJson(e.getValue()));return "{"+String.join(",",items)+"}";}
  if(v instanceof Iterable<?> list){var items=new ArrayList<String>();for(var e:list)items.add(toJson(e));return "["+String.join(",",items)+"]";}
  return "\""+v.toString().replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n")+"\"";
 }
}
''')
stub('io.github.noteblockworldmc.util.Messages','''
import net.kyori.adventure.text.Component;import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
public class Messages {public Messages(Object p){}public void reload(){} public org.bukkit.configuration.file.YamlConfiguration prepareReload(){return new org.bukkit.configuration.file.YamlConfiguration();} public void install(org.bukkit.configuration.file.YamlConfiguration c){}
 public Component get(String k,TagResolver...args){var s=new StringBuilder(k);for(var a:args)s.append(" ").append(a.key()).append("=").append(a.value());return Component.text(s.toString());}}
''')
stub('io.github.noteblockworldmc.playback.VolumeManager','''
public class VolumeManager {public VolumeManager(Object p){}public float getFactor(java.util.UUID id){return 1f;}public void flush(){}}
''')
stub('io.github.noteblockworldmc.command.SongsCommand','public class SongsCommand {public SongsCommand(Object p){}}')
stub('io.github.noteblockworldmc.gui.GuiListener','public class GuiListener {}')
stub('io.github.noteblockworldmc.download.SecureUrlFetcher','''
public class SecureUrlFetcher implements AutoCloseable {public SecureUrlFetcher(long max){}
 public byte[] fetch(String u)throws java.io.IOException{throw new java.io.IOException("Network excluded from regression harness");}public void close(){}}
''')
stub('io.github.noteblockworldmc.pack.VanillaSounds',r'''
import java.nio.file.*;import java.io.*;
public class VanillaSounds {
 private final Path dir;public int resets;
 public VanillaSounds(io.github.noteblockworldmc.NoteblockWorldMCPlugin p){dir=p.getDataFolder().toPath().resolve("vanilla-sounds");}
 public Path get(String n){Path f=dir.resolve(n+".ogg");return Files.isRegularFile(f)?f:null;}
 public boolean hasAll(){return true;}public int ensureDownloaded(){return 0;}public void close(){}
 public void resetLookupFailures(){resets++;}public byte[] resolveCustomSound(String p){return null;}
 public static String normalizeDeclaredPath(String s){String p=s.replace('\\','/').trim();while(p.startsWith("./"))p=p.substring(2);while(p.startsWith("/"))p=p.substring(1);return p;}
}
'''.replace("'\\\\'", "'\\\\'"))
api=(SOURCE/PREFIX/'api/NoteBlockWorldClient.java').read_text()
models=api[api.index('    /** Sort options'):api.index('    private final String userAgent;')]
stub('io.github.noteblockworldmc.api.NoteBlockWorldClient','''
import java.time.Duration;import java.util.*;import java.util.concurrent.*;import io.github.noteblockworldmc.api.model.*;
public class NoteBlockWorldClient {
 public final String url;public final Duration connect,request;public boolean retired;public int refreshes;
 public NoteBlockWorldClient(String u,String v,Duration c,Duration r){url=u;connect=c;request=r;}
 public CompletableFuture<Map<String,Integer>> refreshCategories(){refreshes++;return CompletableFuture.completedFuture(Map.of());}
 public void shutdown(){retired=true;}public void close(){retired=true;}
 public volatile CompletableFuture<String> openUrl;
 public CompletableFuture<String> fetchOpenUrl(String id){var pending=openUrl;return pending!=null?pending:CompletableFuture.failedFuture(new IllegalStateException("Network excluded from regression harness"));}
 public CompletableFuture<byte[]> downloadFile(String u,long n){return CompletableFuture.failedFuture(new IllegalStateException("Network excluded"));}
'''+models+'}\n')

import gui_doubles
gui_doubles.install(STUBS, stub, SOURCE)
STUBS.pop(PREFIX+'download/SecureUrlFetcher.java', None)
STUBS.pop(PREFIX+'pack/VanillaSounds.java', None)
stub('com.google.gson.JsonElement', 'public class JsonElement {public boolean isJsonObject(){return this instanceof JsonObject;}public JsonObject getAsJsonObject(){return (JsonObject)this;}public JsonArray getAsJsonArray(){return (JsonArray)this;}public String getAsString(){return "";} }')
stub('com.google.gson.JsonObject', 'public class JsonObject extends JsonElement {public JsonElement get(String k){return null;}public boolean has(String k){return false;}public JsonObject getAsJsonObject(String k){return null;}public JsonArray getAsJsonArray(String k){return null;}public java.util.Set<String> keySet(){return java.util.Set.of();} }')
stub('com.google.gson.JsonArray', 'public class JsonArray extends JsonElement implements java.lang.Iterable<JsonElement> {public JsonElement get(int n){return null;}public int size(){return 0;}public boolean isEmpty(){return true;}public java.util.Iterator<JsonElement> iterator(){return java.util.Collections.emptyIterator();} }')
stub('com.google.gson.JsonParser', 'public class JsonParser {public static JsonElement parseString(String s){throw new UnsupportedOperationException("External asset JSON excluded from regression harness");} }')
STUBS['org/bukkit/Bukkit.java']=STUBS['org/bukkit/Bukkit.java'].replace('public class Bukkit {', 'public class Bukkit {public static String getMinecraftVersion(){return "26.2";}')

PRODUCTION = [
 'download/SecureUrlFetcher.java','gui/SongBrowserGui.java','gui/GuiListener.java','command/QueryParser.java','command/SongsCommand.java',
 'util/SettingsValidator.java','util/WorkPool.java','util/RequestGate.java','util/DeadlineTimer.java','util/Limits.java','pack/PackHttpServer.java',
 'NoteblockWorldMCPlugin.java','api/model/SongPreview.java','api/model/SongPage.java',
 'download/ImportFiles.java','download/SongDownloadManager.java','nbs/NbsParser.java','nbs/NbsSong.java','nbs/PlayableNote.java',
 'pack/SampleCache.java','pack/VanillaSounds.java','playback/SongCache.java','pack/VorbisSetup.java','pack/OggTools.java','pack/CustomSoundPaths.java','pack/ResourcePackService.java',
 'playback/PlaybackManager.java','playback/PanGeometry.java','playback/PolyphonyAnalyzer.java','util/ConfigEditor.java','util/Throwables.java']

def main():
    java = shutil.which('java')
    if not java: raise SystemExit('Install a JDK (17+ for this harness; 25 for the plugin build).')
    env = os.environ.copy()
    fallback=Path('/usr/lib/jvm/java-17-openjdk-amd64')
    if fallback.exists() and (fallback/'lib/libjli.so').exists():
        env['LD_LIBRARY_PATH']=str(fallback/'lib')+':'+str(fallback/'lib/server')+':'+env.get('LD_LIBRARY_PATH','')
    with tempfile.TemporaryDirectory(prefix='nbw-regression-', dir=ROOT/'tests') as tmp:
        tmp=Path(tmp); src=tmp/'stubs'; classes=tmp/'classes';classes.mkdir()
        for name,body in STUBS.items():
            f=src/name;f.parent.mkdir(parents=True,exist_ok=True);f.write_text(body)
        files=[str(SOURCE/PREFIX/p) for p in PRODUCTION]+[str(f) for f in src.rglob('*.java')]+[str(ROOT/'tests'/name) for name in ['RegressionChecks.java','FixChecks.java','NetworkChecks.java','OptimizationChecks.java','FollowupFixChecks.java','WindowsHttpChecks.java','EdgeFixChecks.java','RemainingFixChecks.java','QueuedTuningFixChecks.java','ReplacedQueueFixChecks.java','TrashRecoveryFixChecks.java','InterruptedImportFixChecks.java','SetupIndexFixChecks.java','Port263Checks.java']]
        # Java 17 cannot resolve the Java 21+ HttpClient shutdownNow method. Only that
        # lifecycle line is omitted in a TEMPORARY test copy; production remains Java 25.
        bounded=(SOURCE/PREFIX/'util/BoundedHttp.java').read_text()
        version=subprocess.run([java,'-version'],env=env,capture_output=True,text=True).stderr
        if 'version "17.' in version:
            bounded=bounded.replace('client.shutdownNow();', '/* Java 17 test runtime: no HttpClient.shutdownNow API */')
        compat=tmp/'compat/BoundedHttp.java';compat.parent.mkdir();compat.write_text(bounded);files.append(str(compat))
        subprocess.run([java,'com.sun.tools.javac.Main','-encoding','UTF-8','-d',str(classes),*files],check=True,env=env)
        subprocess.run([java,'-Dnbw.test.fixture='+str(ROOT/'tests/fixtures/tone.ogg'),'-cp',str(classes),'RegressionChecks',str(tmp/'data')],check=True,env=env)
        subprocess.run([java,'-Djava.io.tmpdir='+str(tmp),'-Xmx256m','-cp',str(classes),'NetworkChecks'],check=True,env=env,timeout=20)
        subprocess.run([java,'-Xmx384m','-Dnbw.test.fixture='+str(ROOT/'tests/fixtures/tone.ogg'),'-cp',str(classes),'FixChecks',str(tmp/'fix-data')],check=True,env=env,timeout=45)
        subprocess.run([java,'-Xmx256m','-Dnbw.test.fixture='+str(ROOT/'tests/fixtures/tone.ogg'),'-cp',str(classes),'OptimizationChecks',str(tmp/'optimization-data')],check=True,env=env,timeout=45)
        subprocess.run([java,'-Xmx256m','-Dnbw.test.fixture='+str(ROOT/'tests/fixtures/tone.ogg'),'-cp',str(classes),'FollowupFixChecks',str(tmp/'followup-data')],check=True,env=env,timeout=30)
        subprocess.run([java,'-Xmx256m','-Dnbw.test.fixture='+str(ROOT/'tests/fixtures/tone.ogg'),'-cp',str(classes),'EdgeFixChecks',str(tmp/'edge-data')],check=True,env=env,timeout=30)
        subprocess.run([java,'-Xmx256m','-Dnbw.test.fixture='+str(ROOT/'tests/fixtures/tone.ogg'),'-cp',str(classes),'RemainingFixChecks',str(tmp/'remaining-data')],check=True,env=env,timeout=30)
        subprocess.run([java,'-Xmx256m','-Dnbw.test.fixture='+str(ROOT/'tests/fixtures/tone.ogg'),'-cp',str(classes),'QueuedTuningFixChecks',str(tmp/'queued-tuning-data')],check=True,env=env,timeout=30)
        subprocess.run([java,'-Xmx256m','-Dnbw.test.fixture='+str(ROOT/'tests/fixtures/tone.ogg'),'-cp',str(classes),'ReplacedQueueFixChecks',str(tmp/'replacement-data')],check=True,env=env,timeout=30)
        subprocess.run([java,'-Xmx256m','-Dnbw.test.fixture='+str(ROOT/'tests/fixtures/tone.ogg'),'-cp',str(classes),'TrashRecoveryFixChecks',str(tmp/'trash-data')],check=True,env=env,timeout=30)
        subprocess.run([java,'-Xmx256m','-Dnbw.test.fixture='+str(ROOT/'tests/fixtures/tone.ogg'),'-cp',str(classes),'InterruptedImportFixChecks',str(tmp/'interrupted-data')],check=True,env=env,timeout=30)
        subprocess.run([java,'-Xmx256m','-Dnbw.test.fixture='+str(ROOT/'tests/fixtures/tone.ogg'),'-cp',str(classes),'SetupIndexFixChecks',str(tmp/'setup-index-data')],check=True,env=env,timeout=30)
        subprocess.run([java,'-Xmx256m','-Dnbw.test.fixture='+str(ROOT/'tests/fixtures/tone.ogg'),'-cp',str(classes),'Port263Checks',str(tmp/'port-data')],check=True,env=env,timeout=60)
        subprocess.run([java,'-Xmx128m','-cp',str(classes),'WindowsHttpChecks',str(tmp)],check=True,env=env,timeout=15)
        subprocess.run([java,'-Djava.io.tmpdir='+str(tmp),'-Dnoteblockworldmc.pack-http-transport=jdk','-Xmx256m','-cp',str(classes),'NetworkChecks'],check=True,env=env,timeout=20)
        # Compile and exercise the actual API client, isolated from the API double used by plugin tests.
        api_classes=tmp/'api-classes';api_classes.mkdir()
        subprocess.run([java,'com.sun.tools.javac.Main','-encoding','UTF-8','-cp',str(classes),'-d',str(api_classes),str(SOURCE/PREFIX/'api/NoteBlockWorldClient.java'),str(ROOT/'tests/ApiCooldownChecks.java')],check=True,env=env)
        subprocess.run([java,'-Xmx128m','-cp',str(api_classes)+os.pathsep+str(classes),'ApiCooldownChecks'],check=True,env=env,timeout=15)
if __name__=='__main__':main()
