package io.github.forgestove.create_cyber_goggles.content.aeronautics;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.JOMLConversion;
import dev.ryanhcode.sable.network.packets.tcp.ServerboundPunchSubLevelPacket;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import io.github.forgestove.create_cyber_goggles.CCG;
import io.github.forgestove.create_cyber_goggles.foundation.events.CCGKey;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult.Type;
import net.neoforged.neoforge.client.event.ClientTickEvent.Post;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;

import java.util.UUID;

import static io.github.forgestove.create_cyber_goggles.foundation.util.CCGUtil.mc;

/**
 * 通过 Sable 的拳击冲量在物理结构上施加偏心力矩，实现无需物理杖的旋转。
 * <p>
 * 服务端处理 {@link ServerboundPunchSubLevelPacket} 时只校验玩家是否站在地面/水中/飞行/爬梯，
 * 以及拳击冷却，并不校验命中点是否真实、距离是否合理。因此客户端可以构造一个偏离质心的
 * 冲量来产生角动量。
 * <p>
 * 单次按下会发送两个方向相反、作用点也相反的冲量：第一次产生角动量和少量线速度，
 * 第二次在冷却结束后抵消线速度并叠加角动量，尽量让结构接近原地旋转。
 */
public final class SubLevelTorqueRotation {
	private static boolean wasDown;
	private static long tick;
	private static long nextPulseTick;
	private static @Nullable PendingPulse pending;

	private SubLevelTorqueRotation() {}

	public static void tick(Post ignoredEvent) {
		tick++;
		LocalPlayer player = mc.player;
		if (player == null || mc.level == null) {
			pending = null;
			wasDown = false;
			nextPulseTick = 0;
			return;
		}
		// 先处理上一组冲量的第二次反向冲量
		if (pending != null) {
			if (pending.targetId != null && !isTargetAlive(pending.targetId)) {
				pending = null;
			} else if (tick >= pending.secondTick) {
				if (canPunch(player)) sendPunch(
					player,
					pending.blockPos,
					pending.offset.negate(new Vector3d()),
					pending.force.negate(new Vector3d())
				);
				pending = null;
			}
		}
		boolean down = CCGKey.torqueRotate.isDown();
		boolean pressed = down && !wasDown;
		wasDown = down;
		if (!down || mc.screen != null) return;
		// 按住时按固定间隔连发；单击则立即触发
		if (!pressed && tick < nextPulseTick) return;
		if (!CCG.config.aeronautics.enableTorqueRotation) return;
		// 上一组还没完成时忽略新的脉冲，避免线速度没有被抵消
		if (pending != null || !canPunch(player)) return;

		BlockHitResult hit = getTargetHit(player);
		if (hit == null) return;
		ClientSubLevel target = Sable.HELPER.getContainingClient(hit.getLocation());
		if (target == null || target.isRemoved()) return;
		SubLevel tracking = Sable.HELPER.getTrackingSubLevel(player);
		// 服务端不允许拳击自己正站着的结构
		if (target == tracking) return;

		Vector3d axis = getAxis(player);
		Vector3d offset = getLever(player, target, hit, axis);
		Vector3d force = axis.cross(offset, new Vector3d());
		if (force.lengthSquared() < 1.0E-8) return;
		force.normalize();
		if (player.isShiftKeyDown()) force.negate();

		BlockPos blockPos = hit.getBlockPos();
		sendPunch(player, blockPos, offset, force);
		pending = new PendingPulse(
			blockPos,
			new Vector3d(offset),
			new Vector3d(force),
			target.getUniqueId(),
			tick + Math.max(1, CCG.config.aeronautics.torquePairDelayTicks)
		);
		// 连发间隔至少覆盖一次对冲，避免第一组还没完成就发第二组
		nextPulseTick = tick + Math.max(
			CCG.config.aeronautics.torqueRepeatTicks,
			CCG.config.aeronautics.torquePairDelayTicks * 2L
		);
	}

	/**
	 * 与 Sable 服务端 {@code ServerboundPunchSubLevelPacket#handle} 的入口条件保持一致。
	 */
	private static boolean canPunch(LocalPlayer player) {
		if (player.isDeadOrDying() || player.isSpectator()) return false;
		return player.onGround() || player.isInWater() || player.getAbilities().flying || player.onClimbable();
	}

	private static @Nullable BlockHitResult getTargetHit(LocalPlayer player) {
		int range = CCG.config.aeronautics.torqueRange;
		var hit = player.pick(range, 1.0F, false);
		return hit.getType() == Type.BLOCK ? (BlockHitResult) hit : null;
	}

	/**
	 * 默认绕世界 Y 轴旋转；开启 {@code torqueUseViewAxis} 后改为绕视线方向旋转。
	 */
	private static Vector3d getAxis(LocalPlayer player) {
		if (CCG.config.aeronautics.torqueUseViewAxis)
			return JOMLConversion.toJOML(player.getLookAngle()).normalize();
		return new Vector3d(0, 1, 0);
	}

	/**
	 * 计算从质心指向作用点的力臂。
	 * <p>
	 * 优先使用准星实际命中的点；如果命中点离质心太近（力臂过小），则退化为玩家到质心的方向。
	 */
	private static Vector3d getLever(LocalPlayer player, ClientSubLevel target, BlockHitResult hit, Vector3d axis) {
		Vector3d com = new Vector3d(target.lastPose().position());
		Vector3d hitGlobal = JOMLConversion.toJOML(hit.getLocation());
		// 子世界射线命中点位于 plot 局部坐标，需要先转换到世界坐标
		target.lastPose().transformPosition(hitGlobal);
		Vector3d lever = removeAxisComponent(hitGlobal.sub(com), axis);
		double length = lever.length();
		if (length < 0.75) {
			lever.set(player.getX() - com.x, player.getEyeY() - com.y, player.getZ() - com.z);
			lever = removeAxisComponent(lever, axis);
			length = lever.length();
		}
		if (length < 1.0E-4) {
			lever.set(1, 0, 0);
			lever = removeAxisComponent(lever, axis);
			length = lever.length();
		}
		double scale = Math.clamp(length, 1.0, 6.0) * CCG.config.aeronautics.torqueLeverScale;
		return lever.normalize().mul(scale);
	}

	private static Vector3d removeAxisComponent(Vector3d vector, Vector3d axis) {
		return vector.fma(-axis.dot(vector), axis);
	}

	private static void sendPunch(LocalPlayer player, BlockPos blockPos, Vector3d offset, Vector3d force) {
		var connection = mc.getConnection();
		if (connection == null) return;
		Vector3d packetDirection = new Vector3d(force).normalize();
		// 服务端期望的方向：站在子世界上时为该子世界的局部方向，否则为世界方向
		SubLevel tracking = Sable.HELPER.getTrackingSubLevel(player);
		if (tracking != null) tracking.logicalPose().transformNormalInverse(packetDirection);
		connection.send(new ServerboundCustomPayloadPacket(
			new ServerboundPunchSubLevelPacket(blockPos, offset, packetDirection)
		));
		player.swing(InteractionHand.MAIN_HAND);
	}

	private static boolean isTargetAlive(UUID uuid) {
		if (mc.level == null) return false;
		SubLevelContainer container = SubLevelContainer.getContainer(mc.level);
		if (container == null) return false;
		SubLevel subLevel = container.getSubLevel(uuid);
		return subLevel != null && !subLevel.isRemoved();
	}

	private record PendingPulse(
		BlockPos blockPos,
		Vector3d offset,
		Vector3d force,
		UUID targetId,
		long secondTick
	) {}
}
