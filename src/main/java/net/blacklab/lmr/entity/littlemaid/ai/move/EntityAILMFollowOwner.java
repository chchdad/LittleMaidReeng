package net.blacklab.lmr.entity.littlemaid.ai.move;

import net.blacklab.lmr.config.LMRConfig;
import net.blacklab.lmr.entity.littlemaid.EntityLittleMaid;
import net.blacklab.lmr.entity.littlemaid.ai.IEntityAILM;
import net.blacklab.lmr.entity.littlemaid.mode.EntityMode_Farmer;
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

	// ====== 真实移动监测系统 ======
	private double lastOwnerX, lastOwnerY, lastOwnerZ;
	private boolean isOwnerMoving = false;
	private long lastCheckTime = 0;
	private int ownerStopTicks = 0;

	public EntityAILMFollowOwner(EntityLittleMaid par1EntityLittleMaid,
			float pSpeed, double pSprintDistSQ) {
		theMaid = par1EntityLittleMaid;
		moveSpeed = pSpeed;
		sprintDist = pSprintDistSQ;
		isEnable = true;
		setMutexBits(3);
	}

	/**
	 * 【核心修复】：基于服务端绝对坐标的时间戳缓存位移检测
	 */
	private boolean checkOwnerMoving(EntityPlayer player) {
		if (player == null) return false;
		long currentTime = theMaid.getEntityWorld().getTotalWorldTime();
		// 同一个 Tick 内不重复计算，节省性能
		if (currentTime == lastCheckTime) return isOwnerMoving;

		double dX = player.posX - this.lastOwnerX;
		double dZ = player.posZ - this.lastOwnerZ;
		this.lastOwnerX = player.posX;
		this.lastOwnerY = player.posY;
		this.lastOwnerZ = player.posZ;
		lastCheckTime = currentTime;

		// 判定为发生实质性位移
		if (dX * dX + dZ * dZ > 0.005D) {
			ownerStopTicks = 0;
			isOwnerMoving = true;
		} else {
			ownerStopTicks++;
			// 给予 0.5 秒 (10 Ticks) 的宽限期，防止玩家短暂转身停顿导致 AI 抽搐
			isOwnerMoving = ownerStopTicks < 10;
		}
		return isOwnerMoving;
	}

	public boolean shouldExecute() {
		if (!isEnable) return false;
		
		Entity owner = theMaid.getMaidMasterEntity();
		if (owner instanceof EntityPlayer) {
			EntityPlayer player = (EntityPlayer) owner;
			
			// 必须每 Tick 调用以保持坐标追踪
			boolean isMoving = checkOwnerMoving(player); 
			double distSq = theMaid.getDistanceSq(player);
			
			// 1. 绝对死线兜底：距离超过 32 格 (1024.0D)，强行释放跟随权限，防止进入未加载区块！
			if (distSq > 1024.0D) {
				return MaidHelper.canStartFollow(theMaid);
			}
			
			// 2. 农夫模式免打扰拦截：只要是农夫，且主人站着没动，直接在最底层掐断跟随！
			if (EntityMode_Farmer.mmode_Farmer.equals(theMaid.getMaidModeString()) && !isMoving) {
				return false; 
			}
		}
		
		return MaidHelper.canStartFollow(theMaid);
	}

	public boolean continueExecuting() {
		if (theMaid.getNavigator().noPath()) return false;

		Entity owner = theMaid.getMaidMasterEntity();
		if (owner instanceof EntityPlayer) {
			EntityPlayer player = (EntityPlayer) owner;
			boolean isMoving = checkOwnerMoving(player);
			double distSq = theMaid.getDistanceSq(player);
			
			if (distSq > 1024.0D) return true;
			
			// 跟随途中如果玩家停下了脚步，立刻掐断跟随，让她回去干活
			if (EntityMode_Farmer.mmode_Farmer.equals(theMaid.getMaidModeString()) && !isMoving) {
				return false; 
			}
		}
		return shouldExecute();
	}

	public void startExecuting() {
		theOwner = theMaid.getMaidMasterEntity();
		field_48310_h = 0;
		if (theOwner != null) {
			this.lastOwnerX = theOwner.posX;
			this.lastOwnerY = theOwner.posY;
			this.lastOwnerZ = theOwner.posZ;
		}
	}

	public void resetTask() {
		theMaid.setSprinting(false);
		theOwner = null;
		theMaid.getNavigator().clearPath();
	}

	public void updateTask() {
		double toDistance = theMaid.getDistanceSq(theOwner);
		
		if (toDistance - theMaid.jobController.getActiveModeClass().getDistanceSqToStartFollow() > 1.0) {
			theMaid.getLookHelper().setLookPositionWithEntity(theOwner, 10F, theMaid.getVerticalFaceSpeed());
		}

		if (theMaid.isSitting()) return;
		
		if(!theMaid.isInWater() || LMRConfig.cfg_test_water_walking){
			theMaid.setSprinting(toDistance > sprintDist);
			if (--field_48310_h > 0) return;
		}

		field_48310_h = 10;
		Path entity = theMaid.getNavigator().getPathToEntityLiving(theOwner);
		theMaid.getNavigator().setPath(entity, moveSpeed);
	}

	@Override
	public void setEnable(boolean pFlag) { isEnable = pFlag; }

	@Override
	public boolean getEnable() { return isEnable; }
}
