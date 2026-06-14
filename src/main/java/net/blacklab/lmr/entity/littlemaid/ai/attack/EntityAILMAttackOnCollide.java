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
		// =======================================================
		// 【ACT动作锁 (Action Lock)】
		// 如果正在执行后撤、停顿或突进，绝对不允许原版引擎中断AI！
		if (actionDelayTimer > 0 || pendingBackstep || pendingDash || retreatTimer > 0) {
			// 除非目标真的死了，否则强制锁死状态机，把这套连招打完！
			if (entityTarget != null && entityTarget.isEntityAlive() && !entityTarget.isDead) {
				return true; 
			}
		}
		// =======================================================

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
		
		// =======================================================
		// 【清空连招记忆，防止鞭尸和跨目标瞎冲！】
		// =======================================================
		retreatTimer = 0;
		actionDelayTimer = 0;
		pendingBackstep = false;
		pendingDash = false;
		isDashBuff = false;
	}

                @Override
        public void updateTask() {
                // =======================================================
                // 【目标一旦死亡立刻终止所有动作】
                // =======================================================
                if (entityTarget == null || entityTarget.isDead || !entityTarget.isEntityAlive()) {
                        resetTask();
                        return;
                }

                                theMaid.getLookHelper().setLookPositionWithEntity(entityTarget, 30F, 30F);
                
                // =======================================================
                // 【🩸 50% 半血狂暴系统】
                // =======================================================
                boolean isBerserk = theMaid.getHealth() <= theMaid.getMaxHealth() * 0.50F;
                theMaid.setBloodsuck(isBerserk); // 半血以下直接红温！
                // =======================================================
                
                // =======================================================
                // 【✨ 修复：精准落地取消机制 (防误判偷Buff)】
                // =======================================================
                // 如果带着突刺Buff，双脚在地上，并且【水平动量已经近乎停止】（说明飞完且没打中）
                if (this.isDashBuff && theMaid.onGround && Math.abs(theMaid.motionX) < 0.05D && Math.abs(theMaid.motionZ) < 0.05D) {
                        this.isDashBuff = false; // 没打中且停下了，没收必杀标记
                        theMaid.hurtResistantTime = 0; // 清空霸体
                }
                // =======================================================




                // =======================================================
                // 【多段式刺客状态机】
                // =======================================================
                // 1. 飞行/滑步期间，大脑挂机
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
                                        
                                        pendingDash = true;
                                        // 如果处于红温狂暴，停顿蓄力时间缩短！
                                        actionDelayTimer = isBerserk ? 8 : 15; 
                                } 
                                // 阶段 B：执行致命突进
                                else if (pendingDash) {
                                        pendingDash = false;
                                        double dX = entityTarget.posX - theMaid.posX;
                                        double dZ = entityTarget.posZ - theMaid.posZ;
                                        double distance = Math.sqrt(dX * dX + dZ * dZ);
                                        if (distance >= 0.0001D) {
                                                theMaid.motionX = (dX / distance) * 0.9D; 
                                                theMaid.motionZ = (dZ / distance) * 0.9D;
                                                theMaid.motionY = 0.2D; 
                                                theMaid.velocityChanged = true;
                                        }
                                        theMaid.hurtResistantTime = 20; 
                                        this.isDashBuff = true; 
                                }
                        }
                        // 停顿期间禁止寻路和攻击
                        return; 
                }
                // =======================================================

			
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
		
		// =======================================================
		// 【✨ 核心修复：突刺动态碰撞箱 (防穿模绝对命中)】
		// =======================================================
		boolean canHit = false;
		if (this.isDashBuff) {
			// 突刺状态下无视常规距离判定！
			// 只要女仆的碰撞箱放大 1 格后，与怪物的碰撞箱产生了重叠，绝对拔刀！
			canHit = theMaid.getEntityBoundingBox().grow(1.0D, 1.0D, 1.0D).intersects(entityTarget.getEntityBoundingBox());
		} else {
			// 正常平A时的经典距离判定
			canHit = theMaid.getDistanceSq(entityTarget.posX, entityTarget.getEntityBoundingBox().minY, entityTarget.posZ) <= attackRange;
		}

		if (!canHit) {
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
                // 【💥 核心增强：突刺命中急刹车与暴击结算】
                // =======================================================
                if (this.isDashBuff) {
                        this.isDashBuff = false; // 消耗掉 Buff
                        
                        // 【命中瞬间强制清空动量，急刹车防穿模！】
                        theMaid.motionX = 0.0D;
                        theMaid.motionY = 0.0D;
                        theMaid.motionZ = 0.0D;
                        theMaid.velocityChanged = true;
                        
                        // 强制大击退
                        double knockX = entityTarget.posX - theMaid.posX;
                        double knockZ = entityTarget.posZ - theMaid.posZ;
                        double d = Math.sqrt(knockX * knockX + knockZ * knockZ);
                        if (d > 0) {
                                entityTarget.addVelocity((knockX / d) * 0.8D, 0.3D, (knockZ / d) * 0.8D);
                        }
                        
                        // 听觉与视觉反馈
                        theMaid.playSound(net.minecraft.init.SoundEvents.ENTITY_PLAYER_ATTACK_CRIT, 1.0F, 1.0F);
                        if (worldObj instanceof net.minecraft.world.WorldServer) {
                                ((net.minecraft.world.WorldServer)worldObj).spawnParticle(
                                        net.minecraft.util.EnumParticleTypes.CRIT, 
                                        entityTarget.posX, entityTarget.posY + entityTarget.height / 2.0F, entityTarget.posZ, 
                                        15, 0.3D, 0.3D, 0.3D, 0.2D
                                );
                        }
                }
                // =======================================================

                // =======================================================
                // 【连招起手式】
                // =======================================================
                // isBerserk 变量在方法顶部定义过了，直接使用即可！
                float triggerChance = isBerserk ? 0.50F : 0.25F;
                if (theMaid.onGround && theMaid.getRNG().nextFloat() < triggerChance) {
                        this.actionDelayTimer = 8; 
                        this.pendingBackstep = true; 
                        theMaid.hurtResistantTime = 40; // 起手即霸体
                }


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
