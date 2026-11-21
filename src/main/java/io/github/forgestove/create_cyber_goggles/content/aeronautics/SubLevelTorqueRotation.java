package io.github.forgestove.create_cyber_goggles.content.aeronautics;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.JOMLConversion;
import dev.ryanhcode.sable.network.packets.tcp.ServerboundPunchSubLevelPacket;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.simulated_team.simulated.SimulatedClient;
import dev.simulated_team.simulated.content.physics_staff.PhysicsStaffClientHandler.ClientDragSession;
import dev.simulated_team.simulated.content.physics_staff.PhysicsStaffItem;
import io.github.forgestove.create_cyber_goggles.CCG;
import io.github.forgestove.create_cyber_goggles.CCGConfig.Aeronautics.TorqueAxis;
import io.github.forgestove.create_cyber_goggles.foundation.events.CCGKey;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult.Type;
import net.neoforged.neoforge.client.event.ClientTickEvent.Pre;
import org.jetbrains.annotations.Nullable;
import org.joml.AxisAngle4d;
import org.joml.Quaterniond;
import org.joml.Vector3d;

import java.util.UUID;

import static io.github.forgestove.create_cyber_goggles.foundation.util.CCGUtil.mc;

/**
 * 物理结构旋转控制器。
 * <p>
 * 默认走物理杖的原有逻辑：玩家先右键进入拖动模式，再由本类在拖动会话中按键修改
 * {@link ClientDragSession#dragOrientation()}，由 {@code PhysicsStaffClientHandler} 每 tick
 * 发送 {@code PhysicsStaffDragPacket}，服务端再用角轴马达旋转结构。
 * <p>
 * 如果玩家没有进入拖动，并且开启了 {@code enablePunchTorqueFallback}，则退化为 Sable 拳击包。
 * 拳击包只校验玩家是否站在地面/水中/飞行/爬梯以及冷却，不校验命中点，因此可以用偏心冲量
 * 产生角动量；但大结构受质量/惯量限制，效果远不如物理杖的角马达。
 */
public final class SubLevelTorqueRotation {
	private static boolean wasDown;
	private static long tick;
	private static long nextPulseTick;
	private static @Nullable PendingPulse pending;
	private static boolean staffWasDown;
	private static @Nullable Vector3d staffAxis;

	private SubLevelTorqueRotation() {}

	public static void tick(Pre ignoredEvent) {
		tick++;
		LocalPlayer player = mc.player;
		if (player == null || mc.level == null) {
			pending = null;
			wasDown = false;
			nextPulseTick = 0;
			staffWasDown = false;
			staffAxis = null;
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
		if (!CCG.config.aeronautics.enableTorqueRotation) {
			wasDown = down;
			return;
		}
		// 有物理杖拖动会话时只走拖动旋转，不再发拳击包
		if (handleStaffDragRotation(player, down)) {
			wasDown = down;
			return;
		}
		if (!CCG.config.aeronautics.enablePunchTorqueFallback) {
			wasDown = down;
			return;
		}
		boolean pressed = down && !wasDown;
		wasDown = down;
		if (!down || mc.screen != null) return;
		// 按住时按固定间隔连发；单击则立即触发
		if (!pressed && tick < nextPulseTick) return;
		// 上一组还没完成时忽略新的脉冲，避免线速度没有被抵消
		if (pending != null || !canPunch(player)) return;

		BlockHitResult hit = getTargetHit(player);
		if (hit == null) return;
		ClientSubLevel target = Sable.HELPER.getContainingClient(hit.getLocation());
		if (target == null || target.isRemoved()) return;
		SubLevel tracking = Sable.HELPER.getTrackingSubLevel(player);
		// 服务端不允许拳击自己正站着的结构
		if (target == tracking) return;

		Vector3d axis = getAxis(player, hit.getDirection());
		Vector3d offset = getLever(player, target, hit, axis);
		Vector3d force = axis.cross(offset, new Vector3d());
		if (force.lengthSquared() < 1.0E-8) return;
		force.normalize();
		if (player.isShiftKeyDown()) force.negate();

		BlockPos blockPos = hit.getBlockPos();
		sendPunch(player, blockPos, offset, force);
		if (CCG.config.aeronautics.torqueCancelLinear) {
			// 记录第二次反向冲量，用于抵消线速度
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
		} else {
			// 重结构模式：不抵消线速度，按更短间隔连续施力
			nextPulseTick = tick + Math.max(1, CCG.config.aeronautics.torqueRepeatTicks);
		}
	}

	/**
	 * 物理杖拖动中的按键旋转。
	 * <p>
	 * 只有在 {@code PhysicsStaffClientHandler} 已经有 {@link ClientDragSession} 时才会生效；
	 * 也就是说玩家必须先自行右键进入拖动模式。修改后的 {@code dragOrientation} 会在
	 * {@code PhysicsStaffClientHandler.tick()} 中随 drag 包发送给服务端。
	 *
	 * @return 如果当前处于物理杖拖动会话中则返回 {@code true}，调用方不再走拳击回退
	 */
	private static boolean handleStaffDragRotation(LocalPlayer player, boolean down) {
		ClientDragSession session = SimulatedClient.PHYSICS_STAFF_CLIENT_HANDLER.getDragSession();
		if (session == null) {
			staffWasDown = false;
			staffAxis = null;
			return false;
		}
		boolean pressed = down && !staffWasDown;
		staffWasDown = down;
		if (!CCG.config.aeronautics.enableStaffDragRotation
			|| !down
			|| mc.screen != null
			|| !PhysicsStaffItem.isHolding(player)) {
			staffAxis = null;
			return true;
		}
		Vector3d axis;
		if (CCG.config.aeronautics.torqueAxis == TorqueAxis.HIT_FACE) {
			// 按住时锁定为按下瞬间指向方块面的法线，避免拖动过程中轴向抖动
			if (pressed || staffAxis == null) {
				BlockHitResult hit = getTargetHit(player);
				staffAxis = getAxis(player, hit != null ? hit.getDirection() : null);
			}
			axis = staffAxis;
		} else {
			axis = getAxis(player, null);
		}
		double degrees = CCG.config.aeronautics.staffRotateDegreesPerTick;
		if (player.isShiftKeyDown()) degrees = -degrees;
		session.dragOrientation().premul(new Quaterniond(
			new AxisAngle4d(Math.toRadians(degrees), axis.x, axis.y, axis.z)
		));
		return true;
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
	 * 根据配置选择旋转轴。
	 * <p>
	 * {@link TorqueAxis#HIT_FACE} 使用指向方块面的法线；没有命中面时退化为世界 Y 轴。
	 */
	private static Vector3d getAxis(LocalPlayer player, @Nullable Direction face) {
		return switch (CCG.config.aeronautics.torqueAxis) {
			case VIEW -> JOMLConversion.toJOML(player.getLookAngle()).normalize();
			case HIT_FACE -> face != null
				? new Vector3d(face.getStepX(), face.getStepY(), face.getStepZ())
				: new Vector3d(0, 1, 0);
			case WORLD_Y -> new Vector3d(0, 1, 0);
		};
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
		double scale = Math.clamp(length, 1.0, CCG.config.aeronautics.torqueMaxLever)
			* CCG.config.aeronautics.torqueLeverScale;
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
