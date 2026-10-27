"""Bukkit/Paper GUI, command and dialog test doubles for run_regressions.py.

The regression harness compiles the real plugin classes against tiny stand-ins
for the server API. This module supplies the stand-ins needed by the song
browser, its click listener and the /nbw command, and widens a few of the base
doubles (Player, Sound, Bukkit, Messages, Component, JavaPlugin) that those
classes touch. It is test-only code; the plugin itself is built against the
real Paper API.
"""


def install(STUBS, stub, SOURCE):
    # The base harness ships placeholders for these; the real classes are
    # compiled from src/main/java instead.
    STUBS.pop('io/github/noteblockworldmc/command/SongsCommand.java', None)
    STUBS.pop('io/github/noteblockworldmc/gui/GuiListener.java', None)

    # ---- annotations -------------------------------------------------------
    stub('org.jetbrains.annotations.NotNull', 'public @interface NotNull {}')
    stub('org.jetbrains.annotations.Nullable', 'public @interface Nullable {}')

    # ---- commands ----------------------------------------------------------
    stub('org.bukkit.command.CommandSender', '''
public interface CommandSender extends net.kyori.adventure.audience.Audience {
 void sendMessage(net.kyori.adventure.text.Component c); boolean hasPermission(String p); String getName();
}''')
    stub('io.papermc.paper.command.brigadier.CommandSourceStack', '''
public interface CommandSourceStack {
 org.bukkit.command.CommandSender getSender(); default org.bukkit.entity.Entity getExecutor(){return null;}
 static CommandSourceStack of(org.bukkit.command.CommandSender sender){return () -> sender;}
}''')
    stub('io.papermc.paper.command.brigadier.BasicCommand', '''
public interface BasicCommand {
 void execute(CommandSourceStack stack, String[] args);
 default java.util.Collection<String> suggest(CommandSourceStack stack, String[] args){return java.util.List.of();}
 default boolean canUse(org.bukkit.command.CommandSender sender){String p=permission();return p==null||sender.hasPermission(p);}
 default String permission(){return null;}
}''')
    stub('org.bukkit.entity.Entity', 'public interface Entity { java.util.UUID getUniqueId(); }')

    # ---- adventure ---------------------------------------------------------
    stub('net.kyori.adventure.audience.Audience', 'public interface Audience {}')
    stub('net.kyori.adventure.text.format.TextDecoration', 'public enum TextDecoration { ITALIC, BOLD }')
    stub('net.kyori.adventure.text.event.ClickEvent', '''
public record ClickEvent(String action, String value) {
 public static ClickEvent runCommand(String command){return new ClickEvent("run_command", command);} }''')
    stub('net.kyori.adventure.text.event.HoverEvent', '''
public record HoverEvent(net.kyori.adventure.text.Component value) {
 public static HoverEvent showText(net.kyori.adventure.text.Component c){return new HoverEvent(c);} }''')
    stub('net.kyori.adventure.text.event.ClickCallback', '''
public interface ClickCallback<T> {
 final class Options { public final int uses; public final java.time.temporal.TemporalAmount lifetime; private Options(int u, java.time.temporal.TemporalAmount l){uses=u;lifetime=l;}
  public static Builder builder(){return new Builder();}
  public static final class Builder { private int uses=1; private java.time.temporal.TemporalAmount life=java.time.Duration.ofHours(12); public Builder uses(int u){uses=u;return this;} public Builder lifetime(java.time.temporal.TemporalAmount t){life=t;return this;} public Options build(){return new Options(uses, life);} } }
}''')
    STUBS['net/kyori/adventure/text/Component.java'] = '''package net.kyori.adventure.text;
public class Component {
 public final String value; public net.kyori.adventure.text.event.ClickEvent click; public net.kyori.adventure.text.event.HoverEvent hover;
 public final java.util.Set<net.kyori.adventure.text.format.TextDecoration> off=new java.util.HashSet<>();
 public Component(String v){value=v;}
 public static Component text(String v){return new Component(v);} public static Component empty(){return text("");} public static Component newline(){return text("\\n");}
 public Component append(Component c){return text(value+c.value);}
 public Component decoration(net.kyori.adventure.text.format.TextDecoration d, boolean on){Component c=copy();if(!on)c.off.add(d);else c.off.remove(d);return c;}
 public Component clickEvent(net.kyori.adventure.text.event.ClickEvent e){Component c=copy();c.click=e;return c;}
 public Component hoverEvent(net.kyori.adventure.text.event.HoverEvent e){Component c=copy();c.hover=e;return c;}
 private Component copy(){Component c=new Component(value);c.click=click;c.hover=hover;c.off.addAll(off);return c;}
 public String toString(){return value;}
}
'''

    # ---- messages double gains the item/raw helpers the GUI and command use
    STUBS['io/github/noteblockworldmc/util/Messages.java'] = STUBS['io/github/noteblockworldmc/util/Messages.java'].replace(
        'public Component get(String k,TagResolver...args)',
        'public Component item(String k,TagResolver...args){return get(k,args).decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC,false);}\n'
        ' public String getRaw(String k,String fallback){return fallback;}\n'
        ' public Component get(String k,TagResolver...args)')

    # ---- API/volume doubles gain the browser and command entry points ------
    STUBS['io/github/noteblockworldmc/api/NoteBlockWorldClient.java'] = STUBS['io/github/noteblockworldmc/api/NoteBlockWorldClient.java'].replace(
        ' public void shutdown()',
        ' public volatile CompletableFuture<io.github.noteblockworldmc.api.model.SongPage> nextPage=CompletableFuture.failedFuture(new IllegalStateException("Network excluded"));\n'
        ' public volatile CompletableFuture<Map<String,Integer>> categories=CompletableFuture.completedFuture(Map.of());\n'
        ' public CompletableFuture<io.github.noteblockworldmc.api.model.SongPage> fetchSongs(SongQuery q){return nextPage;}\n'
        ' public CompletableFuture<Map<String,Integer>> fetchCategories(){return categories;}\n'
        ' public void shutdown()')
    STUBS['io/github/noteblockworldmc/playback/VolumeManager.java'] = '''package io.github.noteblockworldmc.playback;
public class VolumeManager {private final java.util.Map<java.util.UUID,Integer> v=new java.util.concurrent.ConcurrentHashMap<>();public VolumeManager(Object p){}
 public int getVolume(java.util.UUID id){return v.getOrDefault(id,100);}public void setVolume(java.util.UUID id,int n){v.put(id,Math.max(0,Math.min(100,n)));}
 public float getFactor(java.util.UUID id){return getVolume(id)/100f;}public void flush(){}}
'''

    # ---- inventory model ---------------------------------------------------
    stub('org.bukkit.inventory.InventoryHolder', 'public interface InventoryHolder { Inventory getInventory(); }')
    stub('org.bukkit.inventory.Inventory', '''
public class Inventory {
 private final InventoryHolder holder; private final ItemStack[] items;
 public Inventory(InventoryHolder h,int size){holder=h;items=new ItemStack[size];}
 public InventoryHolder getHolder(){return holder;} public int getSize(){return items.length;}
 public ItemStack getItem(int i){return items[i];} public void setItem(int i,ItemStack s){items[i]=s;}
}''')
    stub('org.bukkit.inventory.InventoryView', '''
public class InventoryView { private final Inventory top; public InventoryView(Inventory t){top=t;} public Inventory getTopInventory(){return top;} }''')
    stub('org.bukkit.inventory.meta.ItemMeta', '''
public class ItemMeta {
 public net.kyori.adventure.text.Component name; public java.util.List<net.kyori.adventure.text.Component> lore=java.util.List.of(); public Boolean glint;
 public void customName(net.kyori.adventure.text.Component c){name=c;}
 public void lore(java.util.List<? extends net.kyori.adventure.text.Component> l){lore=java.util.List.copyOf(l);}
 public void setEnchantmentGlintOverride(Boolean b){glint=b;}
}''')
    stub('org.bukkit.inventory.ItemStack', '''
public class ItemStack {
 public final org.bukkit.Material type; private org.bukkit.inventory.meta.ItemMeta meta=new org.bukkit.inventory.meta.ItemMeta();
 public ItemStack(org.bukkit.Material m){type=m;} public org.bukkit.Material getType(){return type;}
 public org.bukkit.inventory.meta.ItemMeta getItemMeta(){return meta;} public boolean setItemMeta(org.bukkit.inventory.meta.ItemMeta m){meta=m;return true;}
}''')
    stub('org.bukkit.Material', '''
public enum Material { MUSIC_DISC_13, MUSIC_DISC_CAT, MUSIC_DISC_BLOCKS, MUSIC_DISC_CHIRP, MUSIC_DISC_FAR, MUSIC_DISC_MALL,
 MUSIC_DISC_MELLOHI, MUSIC_DISC_STAL, MUSIC_DISC_STRAD, MUSIC_DISC_WARD, MUSIC_DISC_WAIT, STRUCTURE_VOID, CLOCK, BARRIER,
 GRAY_STAINED_GLASS_PANE, SLIME_BALL, ARROW, SPYGLASS, COMPARATOR, LEVER, BOOK, PAPER }''')
    stub('org.bukkit.event.inventory.ClickType', '''
public enum ClickType { LEFT, SHIFT_LEFT, RIGHT, SHIFT_RIGHT, MIDDLE, NUMBER_KEY, DOUBLE_CLICK, DROP, CONTROL_DROP, SWAP_OFFHAND, CREATIVE, UNKNOWN;
 public boolean isRightClick(){return this==RIGHT||this==SHIFT_RIGHT;} public boolean isShiftClick(){return this==SHIFT_LEFT||this==SHIFT_RIGHT;} }''')
    stub('org.bukkit.event.inventory.InventoryClickEvent', '''
public class InventoryClickEvent {
 private final org.bukkit.entity.Player who; private final org.bukkit.inventory.Inventory top, clicked; private final int slot; private final ClickType click; public boolean cancelled;
 public InventoryClickEvent(org.bukkit.entity.Player w,org.bukkit.inventory.Inventory t,org.bukkit.inventory.Inventory c,int s,ClickType k){who=w;top=t;clicked=c;slot=s;click=k;}
 public org.bukkit.inventory.Inventory getInventory(){return top;} public org.bukkit.inventory.Inventory getClickedInventory(){return clicked;}
 public org.bukkit.inventory.InventoryView getView(){return new org.bukkit.inventory.InventoryView(top);}
 public org.bukkit.entity.Player getWhoClicked(){return who;} public ClickType getClick(){return click;} public int getRawSlot(){return slot;}
 public void setCancelled(boolean c){cancelled=c;} public boolean isCancelled(){return cancelled;}
}''')
    stub('org.bukkit.event.inventory.InventoryDragEvent', '''
public class InventoryDragEvent { private final org.bukkit.inventory.Inventory top; public boolean cancelled;
 public InventoryDragEvent(org.bukkit.inventory.Inventory t){top=t;} public org.bukkit.inventory.Inventory getInventory(){return top;} public void setCancelled(boolean c){cancelled=c;} }''')

    # ---- dialogs -----------------------------------------------------------
    stub('io.papermc.paper.dialog.DialogResponseView', '''
public interface DialogResponseView { String getText(String key); }''')
    stub('io.papermc.paper.registry.data.dialog.action.DialogActionCallback', '''
@FunctionalInterface public interface DialogActionCallback { void accept(io.papermc.paper.dialog.DialogResponseView r, net.kyori.adventure.audience.Audience a); }''')
    stub('io.papermc.paper.registry.data.dialog.action.DialogAction', '''
public record DialogAction(DialogActionCallback callback, net.kyori.adventure.text.event.ClickCallback.Options options) {
 public static DialogAction customClick(DialogActionCallback c, net.kyori.adventure.text.event.ClickCallback.Options o){return new DialogAction(c,o);} }''')
    stub('io.papermc.paper.registry.data.dialog.ActionButton', '''
public record ActionButton(net.kyori.adventure.text.Component label, io.papermc.paper.registry.data.dialog.action.DialogAction action) {
 public static Builder builder(net.kyori.adventure.text.Component l){return new Builder(l);}
 public static final class Builder { private final net.kyori.adventure.text.Component label; private io.papermc.paper.registry.data.dialog.action.DialogAction action;
  Builder(net.kyori.adventure.text.Component l){label=l;}
  public Builder action(io.papermc.paper.registry.data.dialog.action.DialogAction a){action=a;return this;}
  public ActionButton build(){return new ActionButton(label,action);} } }''')
    stub('io.papermc.paper.registry.data.dialog.input.DialogInput', '''
public interface DialogInput {
 static TextBuilder text(String key, net.kyori.adventure.text.Component label){return new TextBuilder(key);}
 record Text(String key, String initial, int maxLength) implements DialogInput {}
 final class TextBuilder { private final String key; private String initial=""; private int max=32;
  TextBuilder(String k){key=k;} public TextBuilder initial(String s){initial=s;return this;} public TextBuilder maxLength(int m){max=m;return this;}
  public TextBuilder width(int w){return this;} public Text build(){return new Text(key,initial,max);} } }''')
    stub('io.papermc.paper.registry.data.dialog.body.DialogBody', '''
public interface DialogBody { static DialogBody plainMessage(net.kyori.adventure.text.Component c){return new DialogBody(){};} }''')
    stub('io.papermc.paper.registry.data.dialog.DialogBase', '''
public record DialogBase(net.kyori.adventure.text.Component title, java.util.List<? extends io.papermc.paper.registry.data.dialog.input.DialogInput> inputs) {
 public static Builder builder(net.kyori.adventure.text.Component t){return new Builder(t);}
 public static final class Builder { private final net.kyori.adventure.text.Component title; private java.util.List<? extends io.papermc.paper.registry.data.dialog.input.DialogInput> inputs=java.util.List.of();
  Builder(net.kyori.adventure.text.Component t){title=t;}
  public Builder canCloseWithEscape(boolean b){return this;}
  public Builder inputs(java.util.List<? extends io.papermc.paper.registry.data.dialog.input.DialogInput> i){inputs=i;return this;}
  public Builder body(java.util.List<? extends io.papermc.paper.registry.data.dialog.body.DialogBody> b){return this;}
  public DialogBase build(){return new DialogBase(title,inputs);} } }''')
    stub('io.papermc.paper.registry.data.dialog.type.DialogType', '''
public record DialogType(java.util.List<io.papermc.paper.registry.data.dialog.ActionButton> actions, io.papermc.paper.registry.data.dialog.ActionButton exit, int columns) {
 public static DialogType multiAction(java.util.List<io.papermc.paper.registry.data.dialog.ActionButton> a, io.papermc.paper.registry.data.dialog.ActionButton e, int c){return new DialogType(a,e,c);} }''')
    stub('io.papermc.paper.dialog.Dialog', '''
public final class Dialog {
 public io.papermc.paper.registry.data.dialog.DialogBase base; public io.papermc.paper.registry.data.dialog.type.DialogType type;
 public static Dialog create(java.util.function.Consumer<Factory> f){Dialog d=new Dialog();f.accept(new Factory(d));return d;}
 public static final class Factory { private final Dialog d; Factory(Dialog d){this.d=d;} public Builder empty(){return new Builder(d);} }
 public static final class Builder { private final Dialog d; Builder(Dialog d){this.d=d;}
  public Builder base(io.papermc.paper.registry.data.dialog.DialogBase b){d.base=b;return this;}
  public Builder type(io.papermc.paper.registry.data.dialog.type.DialogType t){d.type=t;return this;} } }''')

    # ---- widen the base Player/Sound/Bukkit/JavaPlugin doubles ---------------
    player = STUBS['org/bukkit/entity/Player.java']
    player = player.replace('public class Player {',
        'public class Player implements org.bukkit.command.CommandSender, Entity {\n'
        ' private org.bukkit.inventory.Inventory open; public io.papermc.paper.dialog.Dialog dialog; public final java.util.Set<String> denied=new java.util.HashSet<>();\n'
        ' public org.bukkit.inventory.InventoryView openInventory(org.bukkit.inventory.Inventory i){open=i;return new org.bukkit.inventory.InventoryView(i);}\n'
        ' public org.bukkit.inventory.InventoryView getOpenInventory(){return new org.bukkit.inventory.InventoryView(open==null?new org.bukkit.inventory.Inventory(null,41):open);}\n'
        ' public void closeInventory(){open=null;} public void showDialog(io.papermc.paper.dialog.Dialog d){dialog=d;}\n')
    player = player.replace('public boolean hasPermission(String p){return true;}',
                            'public boolean hasPermission(String p){return !denied.contains(p);}')
    STUBS['org/bukkit/entity/Player.java'] = player
    STUBS['org/bukkit/Sound.java'] = STUBS['org/bukkit/Sound.java'].replace(
        'public enum Sound { ', 'public enum Sound { UI_BUTTON_CLICK, ')
    STUBS['org/bukkit/Bukkit.java'] = STUBS['org/bukkit/Bukkit.java'].replace('public class Bukkit {',
        'public class Bukkit {\n'
        ' public static org.bukkit.inventory.Inventory createInventory(org.bukkit.inventory.InventoryHolder h,int size,net.kyori.adventure.text.Component title){return new org.bukkit.inventory.Inventory(h,size);}\n'
        ' public static org.bukkit.entity.Player getPlayerExact(String name){return server.getOnlinePlayers().stream().filter(p->p.getName().equals(name)).findFirst().orElse(null);}\n')
    STUBS['org/bukkit/plugin/java/JavaPlugin.java'] = STUBS['org/bukkit/plugin/java/JavaPlugin.java'].replace(
        'public void onEnable(){}',
        'public final java.util.Map<String,io.papermc.paper.command.brigadier.BasicCommand> commands=new java.util.HashMap<>();\n'
        ' public void registerCommand(String label,String description,java.util.Collection<String> aliases,io.papermc.paper.command.brigadier.BasicCommand command){commands.put(label,command);}\n'
        ' public void onEnable(){}')
