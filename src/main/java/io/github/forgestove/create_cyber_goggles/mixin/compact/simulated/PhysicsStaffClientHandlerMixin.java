package io.github.forgestove.create_cyber_goggles.mixin.compact.simulated;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.companion.math.JOMLConversion;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.simulated_team.simulated.content.physics_staff.PhysicsStaffAction;
import dev.simulated_team.simulated.content.physics_staff.PhysicsStaffClientHandler;
import dev.simulated_team.simulated.content.physics_staff.PhysicsStaffClientHandler.ClientDragSession;
import dev.simulated_team.simulated.content.physics_staff.PhysicsStaffItem;
import io.github.forgestove.create_cyber_goggles.CCG;
import io.github.forgestove.create_cyber_goggles.content.aeronautics.SubLevelTorqueRotation;
import io.github.forgestove.create_cyber_goggles.content.goggles.ItemSwapUtil;
import io.github.forgestove.create_cyber_goggles.foundation.events.CCGKey;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.*;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import static io.github.forgestove.create_cyber_goggles.foundation.util.CCGUtil.mc;
@Pseudo
@Mixin(PhysicsStaffClientHandler.class)
public abstract class PhysicsStaffClientHandlerMixin {
	@Shadow @Nullable private ClientDragSession dragSession;
	@Inject(method = "tick", at = @At("TAIL"))
	private void tick(CallbackInfo ci) {
		if (dragSession == null) return;
		if (mc.player == null) return;
		if (!CCGKey.correctionSublevel.isDown()) return;
		SubLevel subLevel = dragSession.dragSubLevel();
		Vector3dc localAnchor = dragSession.dragLocalAnchor();
		dragSession = new ClientDragSession(
			subLevel,
			localAnchor,
			new Quaterniond(),
			clampDistance(mc.player.getEyePosition()
				.distanceTo(subLevel.logicalPose().transformPosition(JOMLConversion.toMojang(localAnchor))))
		);
	}
	@Shadow
	protected abstract double clampDistance(double distance);
	/**
	 * 在物理杖进入拖动前记录玩家指向的方块面。
	 * <p>
	 * {@code HIT_FACE} 使用按键瞬间重新射线；{@code DRAG_START_FACE} 则使用这里的记录，
	 * 并在之后用子世界当前 pose 把局部面法线转到世界坐标。
	 */
	@Inject(method = "onItemUsed", at = @At("HEAD"))
	private void captureDragStartFace(PhysicsStaffAction action, CallbackInfo ci) {
		if (action != PhysicsStaffAction.START_DRAG || dragSession != null) return;
		SubLevelTorqueRotation.clearDragStartFace();
		LocalPlayer player = mc.player;
		if (player == null || !PhysicsStaffItem.isHolding(player)) return;
		HitResult hit = player.pick(PhysicsStaffItem.RANGE, 1.0F, false);
		if (!(hit instanceof BlockHitResult blockHit) || blockHit.getType() != HitResult.Type.BLOCK) return;
		SubLevel target = Sable.HELPER.getContainingClient(blockHit.getLocation());
		if (target != null) SubLevelTorqueRotation.captureDragStartFace(target, blockHit.getDirection());
	}
	@WrapMethod(method = "lockSubLevel")
	public void warpSendLockPacket(
		SubLevel subLevel,
		Vec3 hitLocation,
		LocalPlayer player,
		InteractionHand hand,
		Operation<Void> original
	) {
		if (CCG.config.aeronautics.enablePhysicsStaff && mc.player != null && !mc.player.isCreative() && ItemSwapUtil.isSwapped()) return;
		original.call(subLevel, hitLocation, player, hand);
	}
	@WrapMethod(method = "stopDragging")
	public void wrapSendStopPacket(Operation<Void> original) {
		if (CCG.config.aeronautics.enablePhysicsStaff && mc.player != null && !mc.player.isCreative() && ItemSwapUtil.isSwapped()) return;
		original.call();
	}
}
