package io.github.forgestove.create_cyber_goggles.foundation.config;

import io.github.forgestove.create_cyber_goggles.CCG;
import io.github.forgestove.flexconfig.api.ConfigTranslationKeys;
import io.github.forgestove.flexconfig.api.ConfigType;
import io.github.forgestove.flexconfig.api.registration.ConfigHandler;
import io.github.forgestove.flexconfig.foundation.ConfigStore;
import io.github.forgestove.flexconfig.foundation.tree.ValueConfigNode;
import io.github.forgestove.flexconfig.foundation.ui.core.UiText;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.event.ClientTickEvent.Post;

import java.util.*;

import static io.github.forgestove.create_cyber_goggles.foundation.util.CCGUtil.mc;

/**
 * 修复“配置项快捷键”在重启后失效的问题。
 * <p>
 * FlexConfig 会把配置界面上给某个选项绑定的按键持久化到配置文件的 {@code -keybinds-} 段，
 * 但对应的按键回调只有在配置界面构建该配置行（布尔行 / 枚举行）时才会被注册到
 * {@link ConfigHandler}。因此重新启动游戏后，绑定仍然存在却没有任何动作，必须重新绑定一次。
 * <p>
 * 这里在客户端启动后为已经持久化的绑定补注册回调，行为与配置界面中的行保持一致：
 * 布尔值取反，枚举值循环到下一个。只需要执行一次，之后由配置界面自身注册新绑定的回调。
 */
public final class ConfigTriggerKeybinds {
	private static boolean done;

	private ConfigTriggerKeybinds() {}

	public static void tick(Post ignoredEvent) {
		// 首个客户端 tick 执行一次；此时 CCGUtil.mc 已可用，配置也已从磁盘加载
		if (done) return;
		done = true;
		register();
	}

	public static void register() {
		// 访问 CCG.config 以确保配置处理器已经完成初始化并从磁盘读取了绑定
		if (CCG.config == null) return;
		for (var handler : ConfigStore.getHandlers(CCG.ID)) {
			if (handler.getType() != ConfigType.CLIENT) continue;
			var triggers = handler.getAllTriggerKeybinds();
			if (triggers.isEmpty()) continue;
			var root = handler.getConfigTree();
			for (var entry : Map.copyOf(triggers).entrySet()) {
				var path = entry.getKey();
				var node = root.getValueNode(path);
				if (node == null || node.isElement()) continue;
				var type = node.getValueType();
				if (type != boolean.class && type != Boolean.class && !type.isEnum()) continue;
				// 保持已有的按键序列化数据，只补上缺失的回调
				handler.setTriggerKeybind(path, entry.getValue(), () -> trigger(handler, node));
			}
		}
	}

	private static void trigger(ConfigHandler<?, ?> handler, ValueConfigNode node) {
		var casted = handler.cast();
		var config = casted.getConfig();
		var current = node.getActiveValue(config);
		var type = node.getValueType();
		Object next;
		if (type.isEnum()) {
			var constants = type.getEnumConstants();
			next = constants[(((Enum<?>) current).ordinal() + 1) % constants.length];
		} else next = !Boolean.TRUE.equals(current);
		// 与配置界面的写入流程一致，兼容需要重启才生效的选项
		node.setEditingValue(next);
		node.writeEditingToConfig(config);
		casted.save(config);
		showFeedback(node, next);
	}

	/** 在物品栏上方提示切换结果，与 FlexConfig 配置行的反馈保持一致 */
	private static void showFeedback(ValueConfigNode node, Object value) {
		if (mc.player == null) return;
		Component valueText = value instanceof Enum<?> constant
			? Component.translatable(ConfigTranslationKeys.enumValue(CCG.ID, constant))
			: Component.translatable(Boolean.TRUE.equals(value) ? "options.on" : "options.off");
		UiText title = node.getTitle();
		mc.player.displayClientMessage(Component.literal(title.getString() + ": ").append(valueText), true);
	}
}
