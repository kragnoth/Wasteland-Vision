package com.kragnoth.wastelandvision.net;

import com.kragnoth.wastelandvision.WastelandState;
import com.kragnoth.wastelandvision.WastelandVision;
import com.kragnoth.wastelandvision.vision.Vision;
import com.kragnoth.wastelandvision.vision.VisionManager;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.serialization.Codec;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Locale;
import java.util.function.Supplier;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

/**
 * Server side of the visions: which players see which vision (mirrored as the scoreboard tag {@code wv_<vision>}, so loot
 * tables, advancements, quests, commands and other mods can test it), and per-player locks set by command.
 * The state is what each client reports - the vision is still a client-side hallucination.
 */
public final class VisionServer {
    public static final String TAG_PREFIX = "wv_";
    private static final DeferredRegister<AttachmentType<?>> ATTACHMENTS = DeferredRegister.create(NeoForgeRegistries.ATTACHMENT_TYPES, WastelandVision.MODID);
    /** Vision this player is locked into ("" = none). Survives death and relog. */
    public static final Supplier<AttachmentType<String>> LOCK = ATTACHMENTS.register("lock",
            () -> AttachmentType.builder(() -> "").serialize(Codec.STRING).copyOnDeath().build());

    private VisionServer() {}

    public static void init(IEventBus modBus) {
        ATTACHMENTS.register(modBus);
        modBus.addListener(VisionServer::onRegisterPayloads);
        NeoForge.EVENT_BUS.addListener(VisionServer::onCommands);
        NeoForge.EVENT_BUS.addListener(VisionServer::onLogin);
        NeoForge.EVENT_BUS.addListener(VisionServer::onLogout);
    }

    private static void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar r = event.registrar("1").optional();
        r.playToServer(VisionStatePayload.TYPE, VisionStatePayload.CODEC, VisionServer::onState);
        r.playToClient(VisionLockPayload.TYPE, VisionLockPayload.CODEC,
                (p, ctx) -> ctx.enqueueWork(() -> WastelandState.serverLock = p.vision()));
    }

    public static String tagFor(String vision) {
        return TAG_PREFIX + vision.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "_");
    }

    private static void clearTags(ServerPlayer p) {
        for (String t : new ArrayList<>(p.getTags())) if (t.startsWith(TAG_PREFIX)) p.removeTag(t);
    }

    private static void onState(VisionStatePayload p, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer sp)) return;
            clearTags(sp);
            if (p.active() && !p.vision().isEmpty()) sp.addTag(tagFor(p.vision()));
        });
    }

    private static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer sp) {
            clearTags(sp); // the client reports its real state once it's in
            sendLock(sp);
        }
    }

    private static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer sp) clearTags(sp);
    }

    private static void sendLock(ServerPlayer sp) {
        if (sp.connection.hasChannel(VisionLockPayload.TYPE)) PacketDistributor.sendToPlayer(sp, new VisionLockPayload(sp.getData(LOCK)));
    }

    public static void lock(ServerPlayer sp, String vision) {
        sp.setData(LOCK, vision);
        sendLock(sp);
    }

    // ------------------------------------------------------------------ /wastelandvision lock|unlock|status

    private static void onCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> d = event.getDispatcher();
        d.register(Commands.literal("wastelandvision").requires(s -> s.hasPermission(2))
                .then(Commands.literal("lock").then(Commands.argument("targets", EntityArgument.players())
                        .then(Commands.argument("vision", StringArgumentType.word())
                                .suggests((c, b) -> SharedSuggestionProvider.suggest(VisionManager.all().stream().map(Vision::id), b))
                                .executes(c -> lockCmd(c, EntityArgument.getPlayers(c, "targets"), StringArgumentType.getString(c, "vision"))))))
                .then(Commands.literal("unlock").then(Commands.argument("targets", EntityArgument.players())
                        .executes(c -> lockCmd(c, EntityArgument.getPlayers(c, "targets"), ""))))
                .then(Commands.literal("status").then(Commands.argument("target", EntityArgument.player())
                        .executes(VisionServer::status))));
    }

    private static int lockCmd(CommandContext<CommandSourceStack> c, Collection<ServerPlayer> targets, String vision) {
        for (ServerPlayer sp : targets) lock(sp, vision);
        int n = targets.size();
        c.getSource().sendSuccess(() -> Component.literal(vision.isEmpty()
                ? "Unlocked " + n + " player(s)" : "Locked " + n + " player(s) into '" + vision + "'"), true);
        return n;
    }

    private static int status(CommandContext<CommandSourceStack> c) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer sp = EntityArgument.getPlayer(c, "target");
        String seeing = sp.getTags().stream().filter(t -> t.startsWith(TAG_PREFIX)).findFirst().orElse("nothing (or no Wasteland Vision on the client)");
        String lock = sp.getData(LOCK);
        c.getSource().sendSuccess(() -> Component.literal(sp.getGameProfile().getName() + ": sees " + seeing
                + ", lock: " + (lock.isEmpty() ? "none" : lock)), false);
        return 1;
    }
}
