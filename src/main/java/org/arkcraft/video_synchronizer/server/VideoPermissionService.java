package org.arkcraft.video_synchronizer.server;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.server.permission.PermissionAPI;
import net.minecraftforge.server.permission.events.PermissionGatherEvent;
import net.minecraftforge.server.permission.exceptions.UnregisteredPermissionException;
import net.minecraftforge.server.permission.nodes.PermissionNode;
import net.minecraftforge.server.permission.nodes.PermissionTypes;
import org.arkcraft.video_synchronizer.Main;
import org.arkcraft.video_synchronizer.block.VideoManagerBlockEntity;

@Mod.EventBusSubscriber(modid = Main.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class VideoPermissionService {
    public static final PermissionNode<Boolean> CREATE = node("create", true);
    public static final PermissionNode<Boolean> BIND = node("bind", true);
    public static final PermissionNode<Boolean> EDIT_SOURCE = node("edit_source", true);
    public static final PermissionNode<Boolean> CONTROL = node("control", true);
    public static final PermissionNode<Boolean> REMOVE = node("remove", true);
    public static final PermissionNode<Boolean> ADMIN = node("admin", false);

    private VideoPermissionService() {
    }

    @SubscribeEvent
    public static void registerPermissionNodes(PermissionGatherEvent.Nodes event) {
        event.addNodes(CREATE, BIND, EDIT_SOURCE, CONTROL, REMOVE, ADMIN);
    }

    public static boolean canCreate(ServerPlayer player) {
        return isAdmin(player) || permission(player, CREATE);
    }

    public static boolean canBind(ServerPlayer player, VideoManagerBlockEntity manager,
                                  VideoScreenSavedData.ScreenRecord screen) {
        return isAdmin(player) || permission(player, BIND)
                && manager.isOwner(player.getUUID()) && screen.canEdit(player.getUUID());
    }

    public static boolean canBind(ServerPlayer player,
                                  VideoScreenSavedData.ScreenRecord screen) {
        return isAdmin(player) || permission(player, BIND) && screen.canEdit(player.getUUID());
    }

    public static boolean canEditSource(ServerPlayer player,
                                        VideoScreenSavedData.ScreenRecord screen) {
        return isAdmin(player) || permission(player, EDIT_SOURCE)
                && screen.canEdit(player.getUUID());
    }

    public static boolean canControl(ServerPlayer player,
                                     VideoScreenSavedData.ScreenRecord screen) {
        return isAdmin(player) || permission(player, CONTROL)
                && screen.canControl(player.getUUID());
    }

    public static boolean canRemove(ServerPlayer player,
                                    VideoScreenSavedData.ScreenRecord screen) {
        return isAdmin(player) || permission(player, REMOVE) && screen.isOwner(player.getUUID());
    }

    public static boolean canRemove(ServerPlayer player, VideoManagerBlockEntity manager) {
        return isAdmin(player) || permission(player, REMOVE) && manager.isOwner(player.getUUID());
    }

    public static boolean canManage(ServerPlayer player,
                                    VideoScreenSavedData.ScreenRecord screen) {
        return isAdmin(player) || screen.isOwner(player.getUUID());
    }

    public static boolean canViewStatus(ServerPlayer player,
                                        VideoScreenSavedData.ScreenRecord screen) {
        return isAdmin(player) || screen.canView(player.getUUID());
    }

    public static boolean isAdmin(ServerPlayer player) {
        return player.hasPermissions(2) || permission(player, ADMIN);
    }

    private static PermissionNode<Boolean> node(String name, boolean playerDefault) {
        PermissionNode<Boolean> node = new PermissionNode<>(
                Main.MODID, name, PermissionTypes.BOOLEAN,
                (player, playerId, context) -> player != null
                        && (player.hasPermissions(2) || playerDefault));
        node.setInformation(Component.translatable(
                        "permission.video_synchronizer." + name),
                Component.translatable(
                        "permission.video_synchronizer." + name + ".description"));
        return node;
    }

    private static boolean permission(ServerPlayer player, PermissionNode<Boolean> node) {
        try {
            return PermissionAPI.getPermission(player, node);
        } catch (UnregisteredPermissionException | NullPointerException exception) {
            return node.getDefaultResolver().resolve(player, player.getUUID());
        }
    }
}
