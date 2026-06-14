package net.blacklab.lmr.entity.littlemaid.ai.attack;

import firis.lmlib.api.constant.EnumSound;
import net.blacklab.lmr.entity.littlemaid.EntityLittleMaid;
import net.blacklab.lmr.entity.littlemaid.ai.IEntityAILM;
import net.blacklab.lmr.util.helper.MaidHelper;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityCreature;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.ai.EntityAIBase;
import net.minecraft.item.EnumAction;
import net.minecraft.item.ItemStack;
import net.minecraft.pathfinding.Path;
import net.minecraft.util.EnumHand;
import net.minecraft.world.World;

/**
 * メイドさんの直接攻撃系処理
 * @author firis-games
 *
 */
public class EntityAILMAttackOnCollide extends EntityAIBase implements IEntityAILM {

	protected boolean fEnable;

	protected World worldObj;
	protected EntityLittleMaid theMaid;
	protected Entity entityTarget;
	protected float moveSpeed;
	protected boolean isReroute;
	protected Path pathToTarget;
	protected int rerouteTimer;
	protected double attackRange;
	protected int retreatTimer = 0; 
	protected int actionDelayTimer = 0; // 动作之间的停顿间隔
    protected boolean pendingBackstep = false; // 标记：准备后撤
    protected boolean pendingDash = false; // 标记：准备突进
	// 【新增：突进强力一击的 Buff 标记】
    protected boolean isDashBuff = false; 

	public boolean isGuard;


	public EntityAILMAttackOnCollide(EntityLittleMaid par1EntityLittleMaid, float par2, boolean par3) {
		theMaid = par1EntityLittleMaid;
		worldObj = par1EntityLittleMaid.getEntityWorld();
		moveSpeed = par2;
		isReroute = par3;
		isGuard = false;
		setMutexBits(3);
	}

	@Override
	public boolean shouldExecute() {
		Entity lentity = theMaid.getAttackTarget();
		if (!fEnable||theMaid.isMaidWait()) {
			return false;
		}
		if (lentity == null) {
			return false;
		}

		lentity = theMaid.getAttackTarget();
		if(lentity==null) return false;

		entityTarget = lentity;

		pathToTarget = theMaid.getNavigator().getPathToXYZ(entityTarget.posX, entityTarget.posY, entityTarget.posZ);
//		pathToTarget = theMaid.getNavigator().getPathToEntityLiving(entityTarget);
		attackRange = (double)theMaid.width + (double)entityTarget.width + 0.8D;
		attackRange *= attackRange;

		if (theMaid.isFreedom() &&
				!theMaid.isWithinHomeDistanceFromPosition(entityTarget.getPosition())) {
			return false;
		}

		if ((pathToTarget != null) ||
				(theMaid.getDistanceSq(entityTarget.posX, entityTarget.getEntityBoundingBox().minY, entityTarget.posZ) <= attackRange)) {
			return true;
		}
		theMaid.setAttackTarget(null);
		theMaid.setRevengeTarget(null);
//		theMaid.getNavigator().clearPathEntity();
		return false;

	}

	@Override
	public void startExecuting() {
		Entity lentity = theMaid.getAttackTarget();
		/*
		if(theMaid.getMaidModeInt() == LMM_EntityMode_Fencer.mmode_Fencer && lentity instanceof EntityCreeper){
			if(theMaid.getMaidMasterEntity()==null||((EntityCreeper) lentity).getAttackTarget()==null){
			}else if(!((EntityCreeper) lentity).getAttackTarget().equals(theMaid.getMaidMasterEntity())){
			}else{
				theMaid.playLittleMaidSound(theMaid.isBloodsuck() ? LMM_EnumSound.findTarget_B : LMM_EnumSound.findTarget_N, true);
			}
		}else if(!lentity.isDead){
			theMaid.playLittleMaidSound(theMaid.isBloodsuck() ? LMM_EnumSound.findTarget_B : LMM_EnumSound.findTarget_N, true);
		}
		*/
		if(!lentity.isDead){
			theMaid.playLittleMaidVoiceSound(theMaid.isBloodsuck() ? EnumSound.FIND_TARGET_B : EnumSound.FIND_TARGET_N, true);
		}
		theMaid.getNavigator().setPath(pathToTarget, moveSpeed);
		rerouteTimer = 0;
		theMaid.maidAvatar.stopActiveHand();
	}

	@Override
	public boolean shouldContinueExecuting() {
		Entity lentity = theMaid.getAttackTarget();
		if (lentity == null || entityTarget != lentity) {
			return false;
		}

		if (entityTarget.isDead) {
			resetTask();
			return false;
		}
		
		if (!MaidHelper.isTargetReachable(this.theMaid, lentity, 0.0D)) {
			return false;
		}

		if (!entityTarget.isEntityAlive()) {
			return false;
		}

		if (!isReroute) {
			return !theMaid.getNavigator().noPath();
		}

		return true;
	}

	@Override
	public void resetTask() {
		entityTarget = null;
		theMaid.getNavigator().clearPath();
		theMaid.setAttackTarget(null);
		theMaid.setRevengeTarget(null);
//		theMaid.maidAvatar.stopActiveHand();
	}

	        @Override
        public void updateTask() {
                theMaid.getLookHelper().setLookPositionWithEntity(entityTarget, 30F, 30F);

                // =======================================================
                // 【🥷 核心中枢：多段式刺客状态机 (FSM)】
                // =======================================================
                // 1. 飞行/滑步期间，大脑挂机，让物理引擎飞一会儿
                if (retreatTimer > 0) {
                        retreatTimer--;
                        return; 
                }

                // 2. 停顿间隔控制器
                if (actionDelayTimer > 0) {
                        actionDelayTimer--;
                        
                        // 当停顿结束时，触发积压的后续动作！
                        if (actionDelayTimer <= 0) {
                                
                                // 阶段 A：执行战术后撤
                                if (pendingBackstep) {
                                        pendingBackstep = false;
                                        double dX = theMaid.posX - entityTarget.posX;
                                        double dZ = theMaid.posZ - entityTarget.posZ;
                                        double distance = Math.sqrt(dX * dX + dZ * dZ);
                                        if (distance >= 0.0001D) {
                                                theMaid.motionX = (dX / distance) * 0.6D;
                                                theMaid.motionZ = (dZ / distance) * 0.6D;
                                                theMaid.motionY = 0.0D; // 贴地滑步
                                                theMaid.velocityChanged = true;
                                        }
                                        
                                        // 滑步完成后，进入下一阶段：在远处观察并准备突进！
                                        pendingDash = true;
                                        actionDelayTimer = 15; // 在远处停顿 15 刻 (0.75秒)
                                } 
                                                                // 阶段 B：执行致命突进
                                else if (pendingDash) {
                                        pendingDash = false;
                                        double dX = entityTarget.posX - theMaid.posX;
                                        double dZ = entityTarget.posZ - theMaid.posZ;
                                        double distance = Math.sqrt(dX * dX + dZ * dZ);
                                        if (distance >= 0.0001D) {
                                                theMaid.motionX = (dX / distance) * 1.2D; 
                                                theMaid.motionZ = (dZ / distance) * 1.2D;
                                                theMaid.motionY = 0.25D; 
                                                theMaid.velocityChanged = true;
                                        }
                                        
                                        // 【霸体护甲 (I-frames)】
                                        // 给予 20 刻 (1秒) 的完全伤害免疫，确保突进不被打断！
                                        theMaid.hurtResistantTime = 20; 
                                        
                                        // 【点亮必杀标记】
                                        this.isDashBuff = true; 
                                        
                                        retreatTimer = 10; 
                                }

                        }
                        // 在停顿倒计时期间，绝对禁止往前走或攻击（产生硬直感）
                        return; 
                }
			
		if (--rerouteTimer <= 0) {
			if (isReroute) {
				// リルート
				rerouteTimer = 4 + theMaid.getRNG().nextInt(7);
				theMaid.getNavigator().tryMoveToXYZ(entityTarget.posX, entityTarget.posY, entityTarget.posZ, moveSpeed);
			}
			if (theMaid.getEntitySenses().canSee(entityTarget)) {
				// リルート
				rerouteTimer = 4 + theMaid.getRNG().nextInt(7);
				theMaid.getNavigator().tryMoveToXYZ(entityTarget.posX, entityTarget.posY, entityTarget.posZ, moveSpeed);
			} else {
				theMaid.setAttackTarget(null);
				theMaid.setRevengeTarget(null);
			}
		}

		boolean lguard = false;
		if (theMaid.getDistanceSq(entityTarget.posX, entityTarget.getEntityBoundingBox().minY, entityTarget.posZ) > attackRange) {
			if (isGuard && theMaid.isMaskedMaid()) {
				EntityLivingBase lel = null;
				if (entityTarget instanceof EntityCreature) {
					lel = ((EntityCreature)entityTarget).getAttackTarget();
				}
				else if (entityTarget instanceof EntityLivingBase) {
					lel = ((EntityLivingBase)entityTarget).getRevengeTarget();
				}
				if (lel == theMaid) {
					ItemStack li = theMaid.getCurrentEquippedItem();
					if (!li.isEmpty() && li.getItemUseAction() == EnumAction.BLOCK) {
						li.useItemRightClick(worldObj, theMaid.maidAvatar, EnumHand.MAIN_HAND);
						lguard = true;
					}
				}
			}
			return;
		}
		if (theMaid.maidAvatar.isHandActive() && !lguard) {
			theMaid.maidAvatar.stopActiveHand();
		}

		if (!theMaid.getSwingStatusDominant().canAttack()) {
			return;
		}
		// 正面から110度方向が攻撃範囲
		double tdx = entityTarget.posX - theMaid.posX;
		double tdz = entityTarget.posZ - theMaid.posZ;
		double vdx = -Math.sin(theMaid.renderYawOffset * 3.1415926535897932384626433832795F / 180F);
		double vdz = Math.cos(theMaid.renderYawOffset * 3.1415926535897932384626433832795F / 180F);
		double ld = (tdx * vdx + tdz * vdz) / (Math.sqrt(tdx * tdx + tdz * tdz) * Math.sqrt(vdx * vdx + vdz * vdz));
//	        LittleMaidReengaged.Debug(theMaid.renderYawOffset + ", " + ld);
		if (ld < -0.35D) {
			return;
		}

		// 攻撃
		                // 原版攻击
                theMaid.attackEntityAsMob(entityTarget);
                
                // =======================================================
                // 【🥷 连招起手式：触发状态机】
                // =======================================================
                // 普攻打完后，如果在地上，有 25% 的概率启动完整连招
                if (theMaid.onGround && theMaid.getRNG().nextFloat() < 0.25F) {
                        // 不再立刻后滑，而是先原地僵直 4 刻 (0.2秒) 营造打击感
                        this.actionDelayTimer = 4; 
                        // 告诉大脑：停顿结束后，立刻进入后撤阶段
                        this.pendingBackstep = true; 
                }
                // =======================================================

                //theMaid.moveback();
                if (theMaid.jobController.getActiveModeClass().isChangeTartget(entityTarget)) {
                        // 対象を再設定させる
                        theMaid.setAttackTarget(null);
                        theMaid.setRevengeTarget(null);
                        theMaid.getNavigator().clearPath();
                }
                return;
        } // 结束 updateTask() 方法的大括号

        @Override
        public void setEnable(boolean pFlag) {
                fEnable = pFlag;
        }

        @Override
        public boolean getEnable() {
                return fEnable;
        }
} // 结束整个 EntityAILMAttackOnCollide 类的大括号
