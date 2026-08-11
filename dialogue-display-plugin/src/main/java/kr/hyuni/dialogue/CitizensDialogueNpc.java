package kr.hyuni.dialogue;

import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.event.NPCRightClickEvent;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.npc.NPCRegistry;
import org.bukkit.Location;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

import java.util.ArrayList;

final class CitizensDialogueNpc implements Listener {
    private static final String KEY = "rpgmaker-guide-dialogue";
    private static final String DIALOGUE = "초보_상점_이용법";
    private final DialogueDisplayPlugin plugin;

    CitizensDialogueNpc(DialogueDisplayPlugin plugin) {
        this.plugin = plugin;
    }

    void install() {
        NPCRegistry registry = CitizensAPI.getNPCRegistry();
        ArrayList<NPC> guides = new ArrayList<>();
        for (NPC npc : registry) if (DIALOGUE.equals(npc.data().get(KEY, ""))) guides.add(npc);
        guides.sort(java.util.Comparator.comparingInt(NPC::getId));
        NPC guide = guides.isEmpty() ? null : guides.getFirst();
        guides.stream().skip(1).forEach(NPC::destroy);
        if (guide == null) {
            guide = registry.createNPC(EntityType.PLAYER, "대화 안내인");
            guide.data().setPersistent(KEY, DIALOGUE);
        }
        if (!guide.isSpawned() && !guide.spawn(plugin.getServer().getWorlds().getFirst().getSpawnLocation()))
            plugin.getLogger().warning("Citizens dialogue guide NPC could not be spawned.");
        registry.saveToStore();
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    void handleCommand(Player player, String[] args) {
        if (args.length < 3 || !(args[1].equalsIgnoreCase("bind") || args[1].equalsIgnoreCase("unbind"))) {
            player.sendMessage("사용법: /rpgmaker npc bind <NPC ID> <공용명> | unbind <NPC ID>");
            return;
        }
        int id;
        try { id = Integer.parseInt(args[2]); }
        catch (NumberFormatException ignored) {
            player.sendMessage("NPC ID는 숫자여야 합니다.");
            return;
        }
        NPC npc = CitizensAPI.getNPCRegistry().getById(id);
        if (npc == null) {
            player.sendMessage("Citizens NPC " + id + "번을 찾을 수 없습니다.");
            return;
        }
        if (args[1].equalsIgnoreCase("unbind")) {
            npc.data().remove(KEY);
            CitizensAPI.getNPCRegistry().saveToStore();
            player.sendMessage("NPC " + id + "번의 공용 대화 연결을 해제했습니다.");
            return;
        }
        if (args.length != 4 || !plugin.hasPublicDialogue(args[3])) {
            player.sendMessage("연결할 공용 대화문을 찾을 수 없습니다. /rpgmaker public로 확인하세요.");
            return;
        }
        npc.data().setPersistent(KEY, args[3]);
        CitizensAPI.getNPCRegistry().saveToStore();
        player.sendMessage("NPC " + id + "번을 공용 대화문 '" + args[3] + "'에 연결했습니다.");
    }

    @EventHandler(ignoreCancelled = true)
    public void onRightClick(NPCRightClickEvent event) {
        String dialogue = event.getNPC().data().get(KEY, "");
        if (dialogue.isBlank()) return;
        event.setCancelled(true);
        Player player = event.getClicker();
        if (event.getNPC().getEntity() != null) {
            Location view = player.getEyeLocation();
            Location target = event.getNPC().getEntity() instanceof LivingEntity living
                    ? living.getEyeLocation() : event.getNPC().getEntity().getLocation();
            if (view.getWorld().equals(target.getWorld()) && view.distanceSquared(target) > 0.0001) {
                view.setDirection(target.toVector().subtract(view.toVector()));
                player.setRotation(view.getYaw(), view.getPitch());
            }
        }
        plugin.showNpcDialogue(player, dialogue);
    }
}
