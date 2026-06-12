package net.blacklab.lmr.entity.littlemaid.ai.move;

import net.blacklab.lmr.config.LMRConfig;
import net.blacklab.lmr.entity.littlemaid.EntityLittleMaid;
import net.blacklab.lmr.entity.littlemaid.ai.IEntityAILM;
import net.blacklab.lmr.entity.littlemaid.mode.EntityMode_Farmer; // 【新增】引入农夫模式标识
import net.blacklab.lmr.util.helper.MaidHelper;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ai.EntityAIBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.pathfinding.Path;

public class EntityAILMFollowOwner extends EntityAIBase implements IEntityAILM {

	private EntityLittleMaid theMaid;
	private Entity theOwner;
	private float moveSpeed;
	private int field_48310_h;
	protected double sprintDist;
	protected boolean isEnable;

	public EntityAILMFollowOwner(EntityLittleMaid par1EntityLittleMaid,
			float pSpeed, double pSprintDistSQ) {
		theMaid = par1EntityLittleMaid;
		moveSpeed = pSpeed;
		sprintDist = pSprintDistSQ;
		isEnable = true;
		setMutexBits(3);
	}

	/**
	 * 【新增】：精准检测玩家是否正在发生实质性位移
	 */
	private boolean isPlayerMoving(EntityPlayer player) {
		if (player == null) return false;
		double dX = player.posX - player.prevPosX;
		double dZ = player.posZ - player.prevPosZ;
		return (dX * dX + dZ * dZ) > 0.001D;
	}

	/**
	 * Returns whether the EntityAIBase should begin execution.
	 */
	public boolean shouldExecute() {
		if (!isEnable)
			return false;
			
		// ====== 【终极 AI 拦截网：底层接管】 ======
		Entity owner = theMaid.getMaidMasterEntity();
		if (owner instanceof EntityPlayer) {
			EntityPlayer player = (EntityPlayer) owner;
			double distSq = theMaid.getDistanceSq(player);
			
			// 1. 绝对死线兜底：距离超过 32 格 (1024.0D)，不管三七二十一，强行放行跟随AI，防止女仆走丢进入未加载区块！
			if (distSq > 1024.0D) {
				return MaidHelper.canStartFollow(theMaid);
			}
			
			// 2. 农夫模式免打扰拦截：只要是农夫，且主人站着没动，直接在最底层掐断跟随指令！让她安心干活！
			if (EntityMode_Farmer.mmode_Farmer.equals(theMaid.getMaidModeString()) && !isPlayerMoving(player)) {
				return false; // 拦截成功，跟随AI进入休眠，控制权交还给农夫AI
			}
		}
		// ==========================================
		
		return MaidHelper.canStartFollow(theMaid);
	}

	/**
	 * Returns whether an in-progress EntityAIBase should continue executing
	 */
	public boolean continueExecuting() {
		// 这里非常精妙：如果玩家刚才在走，女仆正在跟随；突然玩家停下了，isPlayerMoving(player) 变 false
		// shouldExecute() 就会返回 false，从而打断 continueExecuting，女仆会立刻急刹车回去接着种地！
		return !theMaid.getNavigator().noPath()
				&& shouldExecute();
	}

	/**
	 * Execute a one shot task or start executing a continuous task
	 */
	public void startExecuting() {
		theOwner = theMaid.getMaidMasterEntity();
		field_48310_h = 0;
	}

	/**
	 * Resets the task
	 */
	public void resetTask() {
		theMaid.setSprinting(false);
		theOwner = null;
		theMaid.getNavigator().clearPath();
	}

	/**
	 * Updates the task
	 */
	public void updateTask() {
		double toDistance = theMaid.getDistanceSq(theOwner);
		
		if (toDistance - theMaid.jobController.getActiveModeClass().getDistanceSqToStartFollow() > 1.0) {
			theMaid.getLookHelper().setLookPositionWithEntity(theOwner, 10F, theMaid.getVerticalFaceSpeed());
		}

		if (theMaid.isSitting()) {
			return;
		}
		
		// 指定距離以上ならダッシュ
		// 水上歩行術の場合は水中でも同じ扱いとする
		if(!theMaid.isInWater() || LMRConfig.cfg_test_water_walking){
			theMaid.setSprinting(toDistance > sprintDist);
			if (--field_48310_h > 0) {
				return;
			}
		}

		field_48310_h = 10;

		Path entity = theMaid.getNavigator().getPathToEntityLiving(theOwner);
		theMaid.getNavigator().setPath(entity, moveSpeed);
	}

	@Override
	public void setEnable(boolean pFlag) {
		isEnable = pFlag;
	}

	@Override
	public boolean getEnable() {
		return isEnable;
	}

}
