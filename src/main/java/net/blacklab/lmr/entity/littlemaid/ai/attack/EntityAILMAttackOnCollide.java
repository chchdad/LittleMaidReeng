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
 * メイドさんの直接攻撃系処理 (终极横扫剑技完整版)
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
		// 【ACT动作锁】如果是连招期间，或者【正在冲刺飞行中(isDashBuff)】，强制接管不准打断！
		if (actionDelayTimer > 0 || pendingBackstep || pendingDash || retreatTimer > 0 || isDashBuff) {
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

		// =======================================================
		// 4. 原生寻路模块 (引入狂战士加速突进)
		// =======================================================
		if (--rerouteTimer <= 0) {
			if (isReroute || theMaid.getEntitySenses().canSee(entityTarget)) {
				rerouteTimer = 4 + theMaid.getRNG().nextInt(7);
				
				double distToTarget = theMaid.getDistanceSq(entityTarget);
				float burstSpeed = (distToTarget < 36.0D) ? moveSpeed * 1.5F : moveSpeed;
				theMaid.getNavigator().tryMoveToXYZ(entityTarget.posX, entityTarget.posY, entityTarget.posZ, burstSpeed);
			} else {
				theMaid.setAttackTarget(null);
				theMaid.setRevengeTarget(null);
			}
		}

		// =======================================================
		// 5. 极速斩击判定 (瞬间转身 + 严格攻速 + 剑刃横扫)
		// =======================================================
		double attackRangeSq = (double)theMaid.width + (double)entityTarget.width + 0.8D;
		attackRangeSq *= attackRangeSq;
		double currentDistSq = theMaid.getDistanceSq(entityTarget.posX, entityTarget.getEntityBoundingBox().minY, entityTarget.posZ);
		
		if (currentDistSq <= attackRangeSq) {
			// 贴脸瞬间，强行把身体和头扭向怪物，根除转身慢导致的发呆！
			double tdx = entityTarget.posX - theMaid.posX;
			double tdz = entityTarget.posZ - theMaid.posZ;
			float targetYaw = (float)(Math.atan2(tdz, tdx) * 180.0D / Math.PI) - 90.0F;
			
			// 强行正骨，瞬间锁定目标
			theMaid.rotationYaw = targetYaw;
			theMaid.rotationYawHead = targetYaw;
			theMaid.renderYawOffset = targetYaw;

			// 恢复原版 110度 角度计算 (防止模型视觉上出现“背刺”)
			double vdx = -Math.sin(theMaid.renderYawOffset * 3.1415926535897932384626433832795F / 180F);
			double vdz = Math.cos(theMaid.renderYawOffset * 3.1415926535897932384626433832795F / 180F);
			double ld = (tdx * vdx + tdz * vdz) / (Math.sqrt(tdx * tdx + tdz * tdz) * Math.sqrt(vdx * vdx + vdz * vdz));
			
			// 【修复】：取消无敌帧透支判定，严格遵守当前武器的攻速 CD！
			boolean canSlashNow = (ld >= -0.35D) && theMaid.getSwingStatusDominant().canAttack();

			if (canSlashNow) {
				System.out.println("[LMR-ATTACK-DEBUG] 转身锁定！贴脸瞬间出刀!");
				theMaid.attackEntityAsMob(entityTarget); // 发动单体主目标判定
				
				// ===================================================
				// ====== 新增：剑技横扫模块 (在地面 + 手持剑) ======
				// ===================================================
				if (theMaid.onGround && !theMaid.getHeldItemMainhand().isEmpty() && theMaid.getHeldItemMainhand().getItem() instanceof net.minecraft.item.ItemSword) {
					System.out.println("[LMR-ATTACK-DEBUG] 触发群攻：剑刃横扫！");
					
					// 1. 播放横扫专属音效
					worldObj.playSound(null, theMaid.posX, theMaid.posY, theMaid.posZ, 
						net.minecraft.init.SoundEvents.ENTITY_PLAYER_ATTACK_SWEEP, 
						theMaid.getSoundCategory(), 1.0F, 1.0F);
						
					// 2. 召唤横扫刀光粒子特效
					if (worldObj instanceof net.minecraft.world.WorldServer) {
						((net.minecraft.world.WorldServer)worldObj).spawnParticle(
							net.minecraft.util.EnumParticleTypes.SWEEP_ATTACK, 
							entityTarget.posX, entityTarget.posY + (entityTarget.height / 2.0F), entityTarget.posZ, 
							1, 0.0D, 0.0D, 0.0D, 0.0D
						);
					}
					
					// 3. 【重构】女仆中心 2 格检索 (先画一个大圆)
					java.util.List<EntityLivingBase> list = worldObj.getEntitiesWithinAABB(
						EntityLivingBase.class, 
						theMaid.getEntityBoundingBox().grow(2.0D, 2.0D, 2.0D)
					);
					
					float baseAttackDamage = (float)theMaid.getEntityAttribute(net.minecraft.entity.SharedMonsterAttributes.ATTACK_DAMAGE).getAttributeValue();
					float sweepRatio = net.minecraft.enchantment.EnchantmentHelper.getSweepingDamageRatio(theMaid);
					float sweepDamage = 1.0F + (sweepRatio * baseAttackDamage);
					
					double yawRad = theMaid.renderYawOffset * Math.PI / 180.0D;
					double lookX = -Math.sin(yawRad);
					double lookZ = Math.cos(yawRad);

					for (EntityLivingBase aoeTarget : list) {
						if (aoeTarget != theMaid && aoeTarget != entityTarget && !theMaid.isOnSameTeam(aoeTarget) && theMaid.getDistanceSq(aoeTarget) < 16.0D) {
							double dx = aoeTarget.posX - theMaid.posX;
							double dz = aoeTarget.posZ - theMaid.posZ;
							double distanceXY = Math.sqrt(dx * dx + dz * dz);
							
								if (distanceXY > 0.0001D) {
								double cosTheta = (dx * lookX + dz * lookZ) / distanceXY;
								
								// ===================================================
								// 修复：内圈无视角度，外圈扇形判定
								// 如果怪物贴脸（距离小于 1.2 格），说明它钻进了女仆的身体盲区（比如史莱姆），无视角度强行波及！
								// 如果距离大于 1.2 格，则必须满足正前方的扇形要求（cosTheta > 0.25D）
								// ===================================================
								if (cosTheta > 0.25D || distanceXY <= 1.2D) { 
									System.out.println("[LMR-ATTACK-DEBUG] 📐 扇形/贴脸判定通过！波及目标: " + aoeTarget.getName());
									
									// 造成击退和横扫伤害
									aoeTarget.knockBack(theMaid, 0.4F, (double)MathHelper.sin(theMaid.rotationYaw * 0.017453292F), (double)(-MathHelper.cos(theMaid.rotationYaw * 0.017453292F)));
									aoeTarget.attackEntityFrom(net.minecraft.util.DamageSource.causeMobDamage(theMaid), sweepDamage);
								} else {
									System.out.println("[LMR-ATTACK-DEBUG] 🚫 目标在远端死角，免疫横扫: " + aoeTarget.getName());
								}
							}

						}
					}
				} // <---  修复 1：这里加上大括号，让横扫模块独立闭合！
				
				// ===================================================
				
				float triggerChance = isBerserk ? 0.50F : 0.25F;
				if (theMaid.onGround && theMaid.getRNG().nextFloat() < triggerChance) {
					System.out.println("[LMR-ATTACK-DEBUG] 触发连招体系!");
					this.actionDelayTimer = 8; 
					this.pendingBackstep = true; 
					theMaid.hurtResistantTime = 40; 
				}
			} // <--- 这里结束 canSlashNow 判定
		} // <--- 修复 2：这里加上大括号，闭合 currentDistSq <= attackRangeSq 判定！

		// ====== 封印“自我怀疑”打断机制，防止死锁 ======
		/* if (theMaid.jobController != null && theMaid.jobController.getActiveModeClass() != null) {
			if (theMaid.jobController.getActiveModeClass().isChangeTartget(entityTarget)) {
				System.out.println("[LMR-ATTACK-DEBUG] 被 jobController.isChangeTartget 强制重置目标！");
				theMaid.setAttackTarget(null);
				theMaid.setRevengeTarget(null);
				theMaid.getNavigator().clearPath();
			}
		}
		*/
	} // <--- 这个大括号才是真正的 updateTask 结尾，不漏底了！

	@Override
	public void setEnable(boolean pFlag) {
		fEnable = pFlag;
	}

	@Override
	public boolean getEnable() {
		return fEnable;
	}
}

