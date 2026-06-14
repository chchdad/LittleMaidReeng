package net.blacklab.lmr.entity.littlemaid.ai.attack;

import firis.lmlib.api.constant.EnumSound;
import net.blacklab.lmr.entity.littlemaid.EntityLittleMaid;
import net.blacklab.lmr.entity.littlemaid.ai.IEntityAILM;
import net.blacklab.lmr.util.helper.MaidHelper;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.ai.EntityAIBase;
import net.minecraft.pathfinding.Path;
import net.minecraft.world.World;
import net.minecraft.util.math.MathHelper;

/**
 * メイドさんの直接攻撃系処理 (高频日志抓虫版)
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
	protected int actionDelayTimer = 0; 
	protected boolean pendingBackstep = false; 
	protected boolean pendingDash = false; 
	protected boolean isDashBuff = false; 
	public boolean isGuard;

	// 限制日志刷屏的计数器
	private int logSpamLimiter = 0;

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
		if (!fEnable || theMaid.isMaidWait() || lentity == null) {
			return false;
		}

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
		System.out.println("[LMR-ATTACK-DEBUG] >>> 开启攻击AI! 锁定目标: " + entityTarget.getName());
		Entity lentity = theMaid.getAttackTarget();
		if(!lentity.isDead){
			theMaid.playLittleMaidVoiceSound(theMaid.isBloodsuck() ? EnumSound.FIND_TARGET_B : EnumSound.FIND_TARGET_N, true);
		}
		theMaid.getNavigator().setPath(pathToTarget, moveSpeed);
		rerouteTimer = 0;
		theMaid.maidAvatar.stopActiveHand();
	}

	@Override
	public boolean shouldContinueExecuting() {
		if (actionDelayTimer > 0 || pendingBackstep || pendingDash || retreatTimer > 0) {
			if (entityTarget != null && entityTarget.isEntityAlive() && !entityTarget.isDead) {
				return true; 
			}
		}

		Entity lentity = theMaid.getAttackTarget();
		if (lentity == null || entityTarget != lentity || entityTarget.isDead || !entityTarget.isEntityAlive()) {
			System.out.println("[LMR-ATTACK-DEBUG] --- 中断攻击AI: 目标丢失/死亡/对象变更");
			resetTask();
			return false;
		}
		
		if (!MaidHelper.isTargetReachable(this.theMaid, lentity, 0.0D)) {
			System.out.println("[LMR-ATTACK-DEBUG] --- 中断攻击AI: 目标不可达 (isTargetReachable = false)");
			return false;
		}

		if (!isReroute) {
			if (theMaid.getNavigator().noPath()) {
				System.out.println("[LMR-ATTACK-DEBUG] --- 中断攻击AI: 寻路中断且不允许重新寻路 (noPath = true)");
				return false;
			}
		}

		return true;
	}

	@Override
	public void resetTask() {
		System.out.println("[LMR-ATTACK-DEBUG] <<< 重置任务状态机清空!");
		entityTarget = null;
		theMaid.getNavigator().clearPath();
		theMaid.setAttackTarget(null);
		theMaid.setRevengeTarget(null);
		retreatTimer = 0;
		actionDelayTimer = 0;
		pendingBackstep = false;
		pendingDash = false;
		isDashBuff = false;
	}

	@Override
	public void updateTask() {
		logSpamLimiter++;

		if (entityTarget == null || entityTarget.isDead || !entityTarget.isEntityAlive()) {
			resetTask();
			return;
		}

		theMaid.getLookHelper().setLookPositionWithEntity(entityTarget, 30F, 30F);
		boolean isBerserk = theMaid.getHealth() <= theMaid.getMaxHealth() * 0.50F;
		theMaid.setBloodsuck(isBerserk); 
		
		if (this.isDashBuff) {
			if (theMaid.onGround && Math.abs(theMaid.motionX) < 0.05D && Math.abs(theMaid.motionZ) < 0.05D) {
				if (logSpamLimiter % 10 == 0) System.out.println("[LMR-ATTACK-DEBUG] [警告] DashBuff 被强制没收，女仆停留在原地！");
				this.isDashBuff = false;
				theMaid.hurtResistantTime = 0;
			} else {
				double dX = entityTarget.posX - theMaid.posX;
				double dZ = entityTarget.posZ - theMaid.posZ;
				double distance = Math.sqrt(dX * dX + dZ * dZ);

				theMaid.rotationYaw = (float)(Math.atan2(dZ, dX) * 180.0D / Math.PI) - 90.0F;
				theMaid.renderYawOffset = theMaid.rotationYaw;

				if (distance > 1.5D && distance < 10.0D) {
					theMaid.motionX = (dX / distance) * 0.9D;
					theMaid.motionZ = (dZ / distance) * 0.9D;
					theMaid.velocityChanged = true;
					return; 
				} 
				else if (distance <= 1.5D || theMaid.getEntityBoundingBox().grow(0.8D, 0.8D, 0.8D).intersects(entityTarget.getEntityBoundingBox())) {
					System.out.println("[LMR-ATTACK-DEBUG] 触发吸附处决!");
					theMaid.attackEntityAsMob(entityTarget);
					
					this.isDashBuff = false;
					theMaid.motionX = 0.0D;
					theMaid.motionY = 0.0D;
					theMaid.motionZ = 0.0D;
					theMaid.velocityChanged = true;

					if (entityTarget instanceof EntityLivingBase) {
						((EntityLivingBase)entityTarget).knockBack(theMaid, 1.5F, 
							(double)MathHelper.sin(theMaid.rotationYaw * 0.017453292F), 
							(double)(-MathHelper.cos(theMaid.rotationYaw * 0.017453292F)));
						entityTarget.velocityChanged = true;
					}

					theMaid.playSound(net.minecraft.init.SoundEvents.ENTITY_PLAYER_ATTACK_CRIT, 1.0F, 1.0F);
					if (worldObj instanceof net.minecraft.world.WorldServer) {
						((net.minecraft.world.WorldServer)worldObj).spawnParticle(
							net.minecraft.util.EnumParticleTypes.CRIT, 
							entityTarget.posX, entityTarget.posY + entityTarget.height / 2.0F, entityTarget.posZ, 
							15, 0.3D, 0.3D, 0.3D, 0.2D
						);
					}
					return; 
				} else {
					if (logSpamLimiter % 10 == 0) System.out.println("[LMR-ATTACK-DEBUG] [异常] DashBuff 激活中，但距离超过 10 格！强制重置 Buff！距离: " + distance);
					this.isDashBuff = false;
				}
			}
		}

		if (retreatTimer > 0) {
			retreatTimer--;
			return; 
		}
		if (actionDelayTimer > 0) {
			actionDelayTimer--;
			if (actionDelayTimer <= 0) {
				if (pendingBackstep) {
					pendingBackstep = false;
					double dX = theMaid.posX - entityTarget.posX;
					double dZ = theMaid.posZ - entityTarget.posZ;
					double distance = Math.sqrt(dX * dX + dZ * dZ);
					if (distance >= 0.0001D) {
						theMaid.motionX = (dX / distance) * 0.6D;
						theMaid.motionZ = (dZ / distance) * 0.6D;
						theMaid.motionY = 0.0D; 
						theMaid.velocityChanged = true;
					}
					pendingDash = true;
					actionDelayTimer = isBerserk ? 8 : 15; 
					System.out.println("[LMR-ATTACK-DEBUG] 执行后撤步, 进入蓄力等待...");
				} 
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
					System.out.println("[LMR-ATTACK-DEBUG] 蓄力结束，发射 DashBuff 处决冲击波！");
				}
			}
			return; 
		}

		if (--rerouteTimer <= 0) {
			if (isReroute || theMaid.getEntitySenses().canSee(entityTarget)) {
				rerouteTimer = 4 + theMaid.getRNG().nextInt(7);
				theMaid.getNavigator().tryMoveToXYZ(entityTarget.posX, entityTarget.posY, entityTarget.posZ, moveSpeed);
			} else {
				theMaid.setAttackTarget(null);
				theMaid.setRevengeTarget(null);
			}
		}

		double attackRangeSq = (double)theMaid.width + (double)entityTarget.width + 0.8D;
		attackRangeSq *= attackRangeSq;
		double currentDistSq = theMaid.getDistanceSq(entityTarget.posX, entityTarget.getEntityBoundingBox().minY, entityTarget.posZ);
		
		if (currentDistSq <= attackRangeSq) {
			double tdx = entityTarget.posX - theMaid.posX;
			double tdz = entityTarget.posZ - theMaid.posZ;
			double vdx = -Math.sin(theMaid.renderYawOffset * 3.1415926535897932384626433832795F / 180F);
			double vdz = Math.cos(theMaid.renderYawOffset * 3.1415926535897932384626433832795F / 180F);
			double ld = (tdx * vdx + tdz * vdz) / (Math.sqrt(tdx * tdx + tdz * tdz) * Math.sqrt(vdx * vdx + vdz * vdz));
			
			if (logSpamLimiter % 10 == 0) {
				System.out.println(String.format("[LMR-ATTACK-DEBUG] 贴脸判定: 距:%.2f 范:%.2f | 角度:%.2f (>= -0.35即可) | canAttack: %s", currentDistSq, attackRangeSq, ld, theMaid.getSwingStatusDominant().canAttack()));
			}

			if (ld >= -0.35D && theMaid.getSwingStatusDominant().canAttack()) {
				System.out.println("[LMR-ATTACK-DEBUG] 平A成功发动!");
				theMaid.attackEntityAsMob(entityTarget);
				
				float triggerChance = isBerserk ? 0.50F : 0.25F;
				if (theMaid.onGround && theMaid.getRNG().nextFloat() < triggerChance) {
					System.out.println("[LMR-ATTACK-DEBUG] 触发连招体系!");
					this.actionDelayTimer = 8; 
					this.pendingBackstep = true; 
					theMaid.hurtResistantTime = 40; 
				}
			} else {
				if (logSpamLimiter % 10 == 0 && !theMaid.getSwingStatusDominant().canAttack()) {
					System.out.println("[LMR-ATTACK-DEBUG] [拦截] 虽然贴脸，但 canAttack() CD未转好");
				}
				if (logSpamLimiter % 10 == 0 && ld < -0.35D) {
					System.out.println("[LMR-ATTACK-DEBUG] [拦截] 虽然贴脸，但怪物在视线死角！(ld: " + ld + ")");
				}
			}
		}

		if (theMaid.jobController != null && theMaid.jobController.getActiveModeClass() != null) {
			if (theMaid.jobController.getActiveModeClass().isChangeTartget(entityTarget)) {
				System.out.println("[LMR-ATTACK-DEBUG] 被 jobController.isChangeTartget 强制重置目标！");
				theMaid.setAttackTarget(null);
				theMaid.setRevengeTarget(null);
				theMaid.getNavigator().clearPath();
			}
		}
	}

	@Override
	public void setEnable(boolean pFlag) {
		fEnable = pFlag;
	}

	@Override
	public boolean getEnable() {
		return fEnable;
	}
}
