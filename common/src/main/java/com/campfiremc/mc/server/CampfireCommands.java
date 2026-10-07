package com.campfiremc.mc.server;

import com.campfiremc.mc.backend.BackendConnection;
import com.campfiremc.mc.service.CampfireService;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

public final class CampfireCommands {
    private CampfireCommands() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("bind")
                .then(Commands.argument("code", StringArgumentType.word()).executes(CampfireCommands::bind)));
        dispatcher.register(Commands.literal("campfire").requires(source -> source.hasPermission(2))
                .executes(context -> status(context.getSource()))
                .then(Commands.literal("status").executes(context -> status(context.getSource())))
                .then(Commands.literal("connect").executes(context -> connection(context.getSource(), true)))
                .then(Commands.literal("disconnect").executes(context -> connection(context.getSource(), false)))
                .then(Commands.literal("join").then(Commands.argument("player", StringArgumentType.word())
                        .suggests((context, builder) -> SharedSuggestionProvider.suggest(
                                context.getSource().getServer().getPlayerList().getPlayerNamesArray(), builder))
                        .executes(context -> membership(context, true))))
                .then(Commands.literal("leave").then(Commands.argument("player", StringArgumentType.word())
                        .suggests((context, builder) -> SharedSuggestionProvider.suggest(
                                context.getSource().getServer().getPlayerList().getPlayerNamesArray(), builder))
                        .executes(context -> membership(context, false))))
                .then(Commands.literal("send").then(Commands.argument("message", StringArgumentType.greedyString())
                        .executes(CampfireCommands::send))));
    }

    private static int bind(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        if (!(source.getEntity() instanceof ServerPlayer player)) return fail(source, "此命令只能由玩家执行。");
        CampfireService service = service(source);
        CampfireService.BindOutcome outcome = service == null ? CampfireService.BindOutcome.UNCONFIGURED
                : service.bind(player.getUUID(), StringArgumentType.getString(context, "code"));
        String message = switch (outcome) {
            case SUBMITTED -> "绑定请求已提交，请等待后端结果。";
            case NOT_READY -> "后端连接尚未就绪，请稍后重试。";
            case ALREADY_PENDING -> "已有绑定请求正在处理。";
            case INVALID_CODE -> "验证码必须是首位非零的六位 ASCII 数字。";
            case UNCONFIGURED -> "Campfire 后端尚未配置或已禁用。";
        };
        if (outcome != CampfireService.BindOutcome.SUBMITTED) return fail(source, message);
        return feedback(source, message);
    }

    private static int status(CommandSourceStack source) {
        CampfireService service = service(source);
        if (service == null) return feedback(source, "Campfire: UNCONFIGURED（未配置、已禁用或初始化失败）。");
        BackendConnection.Status status = service.status();
        feedback(source, "Campfire: " + status.state() + "，会话 " + status.sessionId());
        if (status.lastError() != null && !status.lastError().isBlank()) feedback(source, "最近错误: " + status.lastError());
        return 1;
    }

    private static int connection(CommandSourceStack source, boolean connect) {
        CampfireService service = service(source);
        if (service == null) return fail(source, "Campfire 后端尚未配置或已禁用；修改配置后重启服务器。");
        if (connect) service.connect(); else service.disconnect();
        return feedback(source, connect ? "已请求重新认证并连接后端。" : "已停止后端连接及自动重连。");
    }

    private static int membership(CommandContext<CommandSourceStack> context, boolean join) {
        CommandSourceStack source = context.getSource();
        CampfireService service = service(source);
        if (service == null) return fail(source, "Campfire 后端尚未配置或已禁用。");
        UUID uuid = resolve(source, StringArgumentType.getString(context, "player"));
        if (uuid == null) return fail(source, "找不到在线玩家；离线身份请使用完整 UUID。");
        if (join) service.join(uuid); else service.leave(uuid);
        return feedback(source, join ? "已请求加入；只有后端接受后才能同步聊天。" : "已撤销本地聊天同步资格（不会解绑账号）。");
    }

    private static int send(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        CampfireRuntime runtime = CampfireRuntime.get(source.getServer());
        CampfireService service = runtime == null ? null : runtime.service();
        if (service == null) return fail(source, "Campfire 后端尚未配置或已禁用。");
        UUID uuid = source.getEntity() instanceof ServerPlayer player ? player.getUUID() : runtime.consoleUuid();
        if (uuid == null) return fail(source, "非玩家执行者需要配置 consoleUuid，并由后端接受该 UUID 的 join。");
        if (!service.send(uuid, StringArgumentType.getString(context, "message"))) {
            return fail(source, "后端未就绪或此身份尚未获得聊天同步资格。");
        }
        return feedback(source, "消息已提交发送；不代表后端已确认接收。");
    }

    private static UUID resolve(CommandSourceStack source, String value) {
        ServerPlayer player = source.getServer().getPlayerList().getPlayerByName(value);
        if (player != null) return player.getUUID();
        try {
            UUID uuid = UUID.fromString(value);
            return uuid.toString().equalsIgnoreCase(value) ? uuid : null;
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static CampfireService service(CommandSourceStack source) {
        CampfireRuntime runtime = CampfireRuntime.get(source.getServer());
        return runtime == null ? null : runtime.service();
    }

    private static int feedback(CommandSourceStack source, String message) {
        source.sendSuccess(() -> Component.literal(message), false);
        return 1;
    }

    private static int fail(CommandSourceStack source, String message) {
        source.sendFailure(Component.literal(message));
        return 0;
    }
}
