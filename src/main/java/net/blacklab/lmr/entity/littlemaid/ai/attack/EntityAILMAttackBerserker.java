package net.blacklab.lmr.entity.littlemaid.ai.attack;

import firis.lmlib.api.constant.EnumSound;
import net.blacklab.lmr.entity.littlemaid.EntityLittleMaid;
import net.blacklab.lmr.entity.littlemaid.ai.IEntityAILM;
import net.blacklab.lmr.util.helper.MaidHelper;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.ai.EntityAIBase;
import net.minecraft.item.ItemAxe;
import net.minecraft.item.ItemStack;
import net.minecraft.pathfinding.Path;

/**
 * 狂战士独立 AI (基础框架：完美索敌 + 15格跟主追击)
 */
public class EntityAILMAttackBerserker extends EntityAIBase implements IEntityAILM {

	protected boolean fEnable;
	protected EntityLittleMaid theMaid;
	protected EntityLivingBase entityTarget; 
	protected float moveSpeed;
	protected boolean isReroute;
	protected Path pathToTarget;
	protected int rerouteTimer;
	protected double attackRange;

	public EntityAILMAttackBerserker(EntityLittleMaid par1EntityLittleMaid) {
		theMaid = par1EntityLittleMaid;
		moveSpeed = 1.0F; // 基础移速
		isReroute = true;
		setMutexBits(3); 
	}

	@Override
	public boolean shouldExecute() {
		if (theMaid.isMaidWait()) return false;

		// 极其严格的双手双斧判定
		ItemStack mainHand = theMaid.getHeldItemMainhand();
		ItemStack offHand = theMaid.getHeldItemOffhand();
		boolean hasMainAxe = !mainHand.isEmpty() && mainHand.getItem() instanceof ItemAxe;
		boolean hasOffAxe = !offHand.isEmpty() && offHand.getItem() instanceof ItemAxe;
		
		if (!(hasMainAxe && hasOffAxe)) {
			return false;
		}

		EntityLivingBase lentity = theMaid.getAttackTarget();
		
		// 强制仇恨反击锁：被打立刻锁定反击
		if (lentity == null && theMaid.getRevengeTarget() != null && theMaid.getRevengeTarget().isEntityAlive()) {
			theMaid.setAttackTarget(theMaid.getRevengeTarget());
			lentity = theMaid.getAttackTarget();
		}

		if (lentity == null || !lentity.isEntityAlive()) return false;
		
		entityTarget = lentity;
		pathToTarget = theMaid.getNavigator().getPathToXYZ(entityTarget.posX, entityTarget.posY, entityTarget.posZ);
		attackRange = (double)theMaid.width + (double)entityTarget.width + 0.8D;
		attackRange *= attackRange;

		if (theMaid.isFreedom() && !theMaid.isWithinHomeDistanceFromPosition(entityTarget.getPosition())) {
			return false;
		}
		
		if ((pathToTarget != null) || (theMaid.getDistanceSq(entityTarget.posX, entityTarget.getEntityBoundingBox().minY, entityTarget.posZ) <= attackRange)) {
			return true;
		}
		
		theMaid.setAttackTarget(null);
		theMaid.setRevengeTarget(null);
		return false;
	}

	@Override
	public void startExecuting() {
		if(entityTarget != null && !entityTarget.isDead){
			// 狂战士起手直接强制播放红眼狂暴音效
			theMaid.playLittleMaidVoiceSound(EnumSound.FIND_TARGET_B, true);
		}
		
		if (pathToTarget != null) {
			theMaid.getNavigator().setPath(pathToTarget, moveSpeed);
		} else {
			theMaid.getNavigator().clearPath();
		}
		rerouteTimer = 0;
		theMaid.stopActiveHand(); 
		theMaid.maidAvatar.stopActiveHand();
	}

	@Override
	public boolean shouldContinueExecuting() {
		// ==========================================
		// 1. 跟主逻辑 (15格)
		// ==========================================
		if (!theMaid.isFreedom() && theMaid.getMaidMasterEntity() instanceof EntityLivingBase) {
			EntityLivingBase master = (EntityLivingBase) theMaid.getMaidMasterEntity();
			boolean isMasterMoving = Math.abs(master.posX - master.prevPosX) > 0.02D || Math.abs(master.posZ - master.prevPosZ) > 0.02D;
			
			double maxDistSq = isMasterMoving ? 225.0D : 729.0D; 
			
			if (theMaid.getDistanceSq(master) > maxDistSq) {
				theMaid.setPositionAndUpdate(master.posX, master.posY, master.posZ);
				theMaid.playSound(net.minecraft.init.SoundEvents.ENTITY_ENDERMEN_TELEPORT, 0.5F, 1.2F);
				resetTask();
				return false;
			}
		}

		// 2. 目标存活判定
		EntityLivingBase lentity = theMaid.getAttackTarget();
		if (lentity == null || entityTarget != lentity || entityTarget.isDead || !entityTarget.isEntityAlive()) {
			resetTask();
			return false;
		}

		// 3. 寻路可达性判定
		if (!MaidHelper.isTargetReachable(this.theMaid, lentity, 0.0D)) {
			return false;
		}
		if (!isReroute && theMaid.getNavigator().noPath()) {
			return false;
		}
		return true;
	}

	@Override
	public void resetTask() {
		entityTarget = null;
		theMaid.getNavigator().clearPath();
		theMaid.setAttackTarget(null);
		theMaid.setRevengeTarget(null);
		theMaid.stopActiveHand(); 
		theMaid.maidAvatar.stopActiveHand();
	}

	@Override
	public void updateTask() {
		if (entityTarget == null || entityTarget.isDead || !entityTarget.isEntityAlive()) {
			resetTask();
			return;
		}

		// 强制盯住目标
		theMaid.getLookHelper().setLookPositionWithEntity(entityTarget, 30F, 30F);

		// ==========================================
		// 4. 寻路与锁敌逻辑
		// ==========================================
		if (--rerouteTimer <= 0) {
			if (isReroute || theMaid.getEntitySenses().canSee(entityTarget)) {
				rerouteTimer = 4 + theMaid.getRNG().nextInt(7);
				
				double distToTarget = theMaid.getDistanceSq(entityTarget);
				
				// 狂战士基础冲锋移速
				float burstSpeed = moveSpeed * 1.5F; 
				if (distToTarget < 36.0D) {
					// 距离较近时，爆发出更强的追击速度
					burstSpeed = moveSpeed * 1.8F; 
				}
				
				theMaid.getNavigator().tryMoveToXYZ(entityTarget.posX, entityTarget.posY, entityTarget.posZ, burstSpeed);
			} else {
				theMaid.setAttackTarget(null);
				theMaid.setRevengeTarget(null);
			}
		}
		
		// 注意：暂无物理攻击代码，当前她跑到敌人面前后只会紧紧贴住敌人。
	}

	@Override
	public void setEnable(boolean pFlag) { fEnable = pFlag; }
	@Override
	public boolean getEnable() { return fEnable; }
}
