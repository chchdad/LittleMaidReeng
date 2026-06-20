package net.blacklab.lmr.entity.littlemaid.ai.attack;

import firis.lmlib.api.constant.EnumSound;
import net.blacklab.lmr.entity.littlemaid.EntityLittleMaid;
import net.blacklab.lmr.entity.littlemaid.ai.IEntityAILM;
import net.blacklab.lmr.util.helper.MaidHelper;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.EntityLiving;
import net.minecraft.entity.ai.EntityAIBase;
import net.minecraft.pathfinding.Path;
import net.minecraft.world.World;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.DamageSource;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.entity.SharedMonsterAttributes;

/**
 * メイドさんの直接攻撃系処理 (绝境专属：突刺拉扯战术 + 三倍CD + 箭矢侧偏闪避机制)
 */
public class EntityAILMAttackOnCollide extends EntityAIBase implements IEntityAILM {

	protected boolean fEnable;
	protected World worldObj;
	protected EntityLittleMaid theMaid;
	protected EntityLivingBase entityTarget; 
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
	protected int dashTimer = 0; 
	public boolean isGuard;
	protected int rescueBerserkTimer = 0;      
	protected int rescueBerserkCooldown = 0;   
	protected boolean isJumpSlashing = false;
	protected int jumpSlashTimer = 0;
	protected double jumpDirX = 0;
	protected double jumpDirZ = 0;

	// 🌟 绝境求生专属雷达区
	protected boolean isKitingPhase = false;
	protected EntityLivingBase threatTarget = null; 
	
	// 🏹 箭矢闪避冷却
	protected int dodgeCooldown = 0;

	private int logSpamLimiter = 0;

	public EntityAILMAttackOnCollide(EntityLittleMaid par1EntityLittleMaid, float par2, boolean par3) {
		theMaid = par1EntityLittleMaid;
		worldObj = par1EntityLittleMaid.getEntityWorld();
		moveSpeed = par2;
		isReroute = par3;
		isGuard = false;
		setMutexBits(3); 
	}

	private void setMaidOverDrive(int time) {
		try {
			java.lang.reflect.Field field = net.blacklab.lmr.entity.littlemaid.EntityLittleMaid.class.getDeclaredField("maidOverDriveTime");
			field.setAccessible(true);
			Object overDriveObj = field.get(this.theMaid);
			if (overDriveObj != null) {
				java.lang.reflect.Method method = overDriveObj.getClass().getMethod("setValue", int.class);
				method.invoke(overDriveObj, time);
			}
		} catch (Exception e) {
			System.err.println("[LMR-ATTACK-DEBUG] 反射修改红温状态失败: " + e.getMessage());
		}
	}

	private int getWeaponCooldown() {
		float attackSpeed = 4.0F; 
		net.minecraft.entity.ai.attributes.IAttributeInstance speedAttr = theMaid.getEntityAttribute(SharedMonsterAttributes.ATTACK_SPEED);
		if (speedAttr != null) {
			attackSpeed = (float) speedAttr.getAttributeValue();
		}
		return (int)(20.0F / Math.max(0.1F, attackSpeed));
	}

	private void fleeLikeFromCreeper(EntityLivingBase threat) {
		if (theMaid.getNavigator().noPath() || logSpamLimiter % 5 == 0) {
			net.minecraft.util.math.Vec3d safePos = net.minecraft.entity.ai.RandomPositionGenerator.findRandomTargetBlockAwayFrom(
				theMaid, 16, 7, new net.minecraft.util.math.Vec3d(threat.posX, threat.posY, threat.posZ)
			);
			if (safePos != null) {
				theMaid.getNavigator().tryMoveToXYZ(safePos.x, safePos.y, safePos.z, moveSpeed * 1.35F); 
			}
		}
	}

	// 🌟 全局记忆同步锁
	private void syncKitingState(EntityLivingBase target) {
		if (target == null) return;
		float hpPct = theMaid.getHealth() / theMaid.getMaxHealth();
		
		if (hpPct <= 0.25F) { 
			if (!this.isKitingPhase) {
				this.isKitingPhase = true;
				this.isDashBuff = false;
				this.pendingDash = false;
				this.pendingBackstep = false;
				if (this.isJumpSlashing) {
					this.isJumpSlashing = false;
					theMaid.setNoGravity(false);
				}
				this.setMaidOverDrive(0);
				this.rescueBerserkTimer = 0;
				theMaid.getNavigator().clearPath();
			}
			this.threatTarget = target; 
			theMaid.setAttackTarget(null);    
			theMaid.setRevengeTarget(null);
		} else if (hpPct >= 0.40F) { 
			if (this.isKitingPhase) {
				this.isKitingPhase = false;
				if (this.threatTarget != null && this.threatTarget.isEntityAlive()) {
					theMaid.setAttackTarget(this.threatTarget);
				}
				this.threatTarget = null;
			}
		}
	}

	@Override
	public boolean shouldExecute() {
		EntityLivingBase lentity = theMaid.getAttackTarget();
		
		if (this.isKitingPhase && this.threatTarget != null && this.threatTarget.isEntityAlive()) {
			lentity = this.threatTarget;
		}

		if (!fEnable || theMaid.isMaidWait() || lentity == null) return false;
		
		syncKitingState(lentity);
		entityTarget = lentity;
		
		if (!this.isKitingPhase) {
			pathToTarget = theMaid.getNavigator().getPathToXYZ(entityTarget.posX, entityTarget.posY, entityTarget.posZ);
		} else {
			pathToTarget = null; 
		}
		
		attackRange = (double)theMaid.width + (double)entityTarget.width + 0.8D;
		attackRange *= attackRange;

		if (theMaid.isFreedom() && !theMaid.isWithinHomeDistanceFromPosition(entityTarget.getPosition())) {
			return false;
		}
		if (this.isKitingPhase || (pathToTarget != null) || (theMaid.getDistanceSq(entityTarget.posX, entityTarget.getEntityBoundingBox().minY, entityTarget.posZ) <= attackRange)) {
			return true;
		}
		
		theMaid.setAttackTarget(null);
		theMaid.setRevengeTarget(null);
		return false;
	}

	@Override
	public void startExecuting() {
		EntityLivingBase lentity = this.isKitingPhase ? this.threatTarget : theMaid.getAttackTarget();
		if(lentity != null && !lentity.isDead){
			theMaid.playLittleMaidVoiceSound(theMaid.isBloodsuck() ? EnumSound.FIND_TARGET_B : EnumSound.FIND_TARGET_N, true);
		}
		
		if (!this.isKitingPhase && pathToTarget != null) {
			theMaid.getNavigator().setPath(pathToTarget, moveSpeed);
		} else {
			theMaid.getNavigator().clearPath();
		}
		rerouteTimer = 0;
		theMaid.maidAvatar.stopActiveHand();
	}

	@Override
	public boolean shouldContinueExecuting() {
		if (actionDelayTimer > 0 || pendingBackstep || pendingDash || retreatTimer > 0 || isDashBuff || isJumpSlashing || isKitingPhase) {
			EntityLivingBase targetToCheck = isKitingPhase ? threatTarget : entityTarget;
			if (targetToCheck != null && targetToCheck.isEntityAlive() && !targetToCheck.isDead) {
				return true; 
			}
		}

		if (!theMaid.isFreedom() && theMaid.getMaidMasterEntity() instanceof EntityLivingBase) {
			EntityLivingBase master = (EntityLivingBase) theMaid.getMaidMasterEntity();
			boolean isMasterMoving = Math.abs(master.posX - master.prevPosX) > 0.02D || Math.abs(master.posZ - master.prevPosZ) > 0.02D;
			double maxDistSq = isMasterMoving ? 100.0D : 729.0D; 
			
			if (theMaid.getDistanceSq(master) > maxDistSq) {
				theMaid.setPositionAndUpdate(master.posX, master.posY, master.posZ);
				theMaid.playSound(net.minecraft.init.SoundEvents.ENTITY_ENDERMEN_TELEPORT, 0.5F, 1.2F);
				resetTask();
				return false;
			}
		}

		EntityLivingBase lentity = theMaid.getAttackTarget();
		if (this.isKitingPhase && this.threatTarget != null && this.threatTarget.isEntityAlive()) {
			lentity = this.threatTarget;
		}

		if (lentity == null || entityTarget != lentity || entityTarget.isDead || !entityTarget.isEntityAlive()) {
			resetTask();
			return false;
		}
		
		if (!isReroute && !this.isKitingPhase) {
			if (theMaid.getNavigator().noPath()) {
				return false;
			}
		}
		return true;
	}

	@Override
	public void resetTask() {
		entityTarget = null;
		theMaid.getNavigator().clearPath();
		theMaid.setAttackTarget(null);
		theMaid.setRevengeTarget(null);
		actionDelayTimer = 0;
		pendingBackstep = false;
		pendingDash = false;
		isDashBuff = false;
		isJumpSlashing = false;
		theMaid.setNoGravity(false);
		
		if (isKitingPhase) {
			threatTarget = null;
		}

		if (rescueBerserkTimer > 0) {
			rescueBerserkTimer = 0;
			rescueBerserkCooldown = 200;
			this.setMaidOverDrive(0);
		}
	}

	@Override
	public void updateTask() {
		logSpamLimiter++;

		EntityLivingBase targetToProcess = this.isKitingPhase ? this.threatTarget : this.entityTarget;

		if (targetToProcess == null || targetToProcess.isDead || !targetToProcess.isEntityAlive()) {
			resetTask();
			return;
		}

		if (!this.isKitingPhase) {
			theMaid.getLookHelper().setLookPositionWithEntity(targetToProcess, 30F, 30F);
		}
		
		syncKitingState(targetToProcess);
		
		// =========================================================
		// 🏹 核心黑科技：防箭矢侧滑闪避机制 (全阶段生效)
		// =========================================================
		if (this.dodgeCooldown > 0) this.dodgeCooldown--;

		if (this.dodgeCooldown <= 0) {
			// 扫描周围 6 格内的所有实体
			java.util.List<Entity> projectiles = worldObj.getEntitiesWithinAABBExcludingEntity(theMaid, theMaid.getEntityBoundingBox().grow(6.0D, 2.0D, 6.0D));
			for (Entity proj : projectiles) {
				// 如果它是箭、投掷物或者火球
				if (proj instanceof net.minecraft.entity.projectile.EntityArrow || 
					proj instanceof net.minecraft.entity.projectile.EntityThrowable || 
					proj instanceof net.minecraft.entity.projectile.EntityFireball) {
					
					// 且正在高速飞行
					if (proj.motionX * proj.motionX + proj.motionZ * proj.motionZ > 0.05D) {
						// 1. 获取箭矢飞行的方向
						double dodgeDirX = -proj.motionZ;
						double dodgeDirZ = proj.motionX;
						
						double len = Math.sqrt(dodgeDirX * dodgeDirX + dodgeDirZ * dodgeDirZ);
						if (len > 0.001D) {
							dodgeDirX /= len;
							dodgeDirZ /= len;
							
							// 2. 随机向左侧或右侧翻滚
							if (theMaid.getRNG().nextBoolean()) {
								dodgeDirX = -dodgeDirX;
								dodgeDirZ = -dodgeDirZ;
							}
							
							// 3. 施加爆发性的侧移速度和小跳跃
							theMaid.motionX = dodgeDirX * 0.6D;
							theMaid.motionZ = dodgeDirZ * 0.6D;
							if (theMaid.onGround) {
								theMaid.motionY = 0.25D; 
							}
							
							// 4. 进入 40~60 刻的闪避冷却 (相当于小白拉弓的时间)
							this.dodgeCooldown = 40 + theMaid.getRNG().nextInt(20); 
							System.err.println("[LMR-SPEED-DEBUG] 💨 侦测到危险飞行物！战术侧滑闪避！");
							break; // 躲一次就够了，结束扫描
						}
					}
				}
			}
		}

		if (this.isKitingPhase) {
			if (this.actionDelayTimer > 0) this.actionDelayTimer--;

			theMaid.addPotionEffect(new net.minecraft.potion.PotionEffect(net.minecraft.init.MobEffects.RESISTANCE, 10, 4, false, false));

			if (theMaid.hurtTime == 10) {
				theMaid.getNavigator().clearPath(); 
				theMaid.heal(theMaid.getMaxHealth() * 0.1F); 
				if (theMaid.getHealth() < 1.0F) theMaid.setHealth(1.0F); 

				double tdx = targetToProcess.posX - theMaid.posX;
				double tdz = targetToProcess.posZ - theMaid.posZ;
				float targetYaw = (float)(Math.atan2(tdz, tdx) * 180.0D / Math.PI) - 90.0F;
				theMaid.rotationYaw = targetYaw;
				theMaid.rotationYawHead = targetYaw;
				theMaid.renderYawOffset = targetYaw;

				theMaid.maidAvatar.setActiveHand(net.minecraft.util.EnumHand.OFF_HAND);
				theMaid.playSound(net.minecraft.init.SoundEvents.ITEM_SHIELD_BLOCK, 1.0F, 0.8F + theMaid.getRNG().nextFloat() * 0.4F);
				
				this.actionDelayTimer = getWeaponCooldown() + 20; 
				return; 
			}

			if (theMaid.hurtTime <= 0 && theMaid.maidAvatar.isHandActive()) {
				theMaid.maidAvatar.stopActiveHand();
			}
			
			// =========================================================
			// 🗡️ 绝境突刺状态机 (冲过去砍)
			// =========================================================
			if (this.isDashBuff) {
				this.dashTimer++;
				
				// 突刺最长持续 15 刻，如果撞墙或速度变慢就取消
				if (this.dashTimer > 15 || (theMaid.onGround && Math.abs(theMaid.motionX) < 0.05D && Math.abs(theMaid.motionZ) < 0.05D && this.dashTimer > 3) || theMaid.collidedHorizontally) {
					this.isDashBuff = false;
				} else {
					double dX = targetToProcess.posX - theMaid.posX;
					double dZ = targetToProcess.posZ - theMaid.posZ;
					double distance = Math.sqrt(dX * dX + dZ * dZ);

					theMaid.rotationYaw = (float)(Math.atan2(dZ, dX) * 180.0D / Math.PI) - 90.0F;
					theMaid.renderYawOffset = theMaid.rotationYaw;

					// 冲锋接近过程
					if (distance > 1.5D && distance < 10.0D) {
						theMaid.motionX = (dX / distance) * 0.40D; // 突刺速度略快
						theMaid.motionZ = (dZ / distance) * 0.40D;
						return; 
					} 
					// 贴脸！发生碰撞！
					else if (distance <= 1.5D || theMaid.getEntityBoundingBox().grow(0.8D).intersects(targetToProcess.getEntityBoundingBox())) {
						// 调用最原汁原味的攻击
						boolean isHit = theMaid.attackEntityAsMob(targetToProcess);
						
						this.isDashBuff = false;
						// 🌟【核心要求】攻击冷却几倍：这里设为正常冷却的 3 倍！
						this.actionDelayTimer = getWeaponCooldown() * 3; 
						
						theMaid.motionX = 0.0D; 
						theMaid.motionZ = 0.0D;
						theMaid.velocityChanged = true; 

						if (isHit) {
							// 额外击退，拉开身位
							targetToProcess.knockBack(theMaid, 1.2F, (double)MathHelper.sin(theMaid.rotationYaw * 0.017453292F), (double)(-MathHelper.cos(theMaid.rotationYaw * 0.017453292F)));
							theMaid.playSound(net.minecraft.init.SoundEvents.ENTITY_PLAYER_ATTACK_CRIT, 1.0F, 1.0F);
							if (worldObj instanceof net.minecraft.world.WorldServer) ((net.minecraft.world.WorldServer)worldObj).spawnParticle(net.minecraft.util.EnumParticleTypes.CRIT, targetToProcess.posX, targetToProcess.posY + targetToProcess.height / 2.0F, targetToProcess.posZ, 15, 0.3D, 0.3D, 0.3D, 0.2D);
						}
						
						System.err.println("[LMR-SPEED-DEBUG] ⚔️ 绝境突刺命中！进入三倍漫长冷却！");
						return; 
					} else {
						this.isDashBuff = false;
					}
				}
			}

			double distSq = theMaid.getDistanceSq(targetToProcess);
			boolean isChasedByMonster = (targetToProcess.getRevengeTarget() == theMaid);
			if (targetToProcess instanceof EntityLiving) {
				isChasedByMonster = isChasedByMonster || (((EntityLiving)targetToProcess).getAttackTarget() == theMaid);
			}

			// 危险报警距离设为 2.5格 (6.25D)
			boolean tooCloseToThreat = isChasedByMonster && (distSq < 6.25D);

			// =========================================================
			// 🛡️ 战术分支：拉扯倒车 vs 发起突刺
			// =========================================================
			
			// 分支 1: 如果冷却好了，且没在突刺中，距离在 10 格内 -> 💥 发起突刺！
			if (this.actionDelayTimer <= 0 && !this.isDashBuff && distSq <= 100.0D) {
				this.isDashBuff = true;
				this.dashTimer = 0;
				theMaid.playSound(net.minecraft.init.SoundEvents.ENTITY_PLAYER_ATTACK_NODAMAGE, 1.0F, 0.8F);
				theMaid.swingArm(net.minecraft.util.EnumHand.MAIN_HAND);
				System.err.println("[LMR-SPEED-DEBUG] 💨 冷却就绪！发起绝境突刺！");
				return;
			}
			
			// 分支 2: 如果武器在冷却中，或者怪物靠得太近 -> 🚙 物理防壁咚拉扯倒车！ (你要求的保留不变)
			if (this.actionDelayTimer > 0 || tooCloseToThreat) {
				double dX = theMaid.posX - targetToProcess.posX;
				double dZ = theMaid.posZ - targetToProcess.posZ;
				double distance = Math.sqrt(dX * dX + dZ * dZ);

				if (distance > 0.0001D) {
					theMaid.getNavigator().clearPath();
					
					float targetYaw = (float)(Math.atan2(targetToProcess.posZ - theMaid.posZ, targetToProcess.posX - theMaid.posX) * 180.0D / Math.PI) - 90.0F;
					theMaid.rotationYaw = targetYaw;
					theMaid.rotationYawHead = targetYaw;
					theMaid.renderYawOffset = targetYaw;

					theMaid.motionX = (dX / distance) * 0.28D;
					theMaid.motionZ = (dZ / distance) * 0.28D;

					if (theMaid.collidedHorizontally && theMaid.onGround) {
						theMaid.getJumpHelper().setJumping();
					}
				}
				if (logSpamLimiter % 15 == 0) {
					if (tooCloseToThreat) System.err.println("[LMR-SPEED-DEBUG] 🚨 怪物贴近！倒车拉扯中！");
					else System.err.println("[LMR-SPEED-DEBUG] ⏱️ 漫长冷却中！倒车拉扯中！");
				}
			} 
			// 分支 3: 距离过远，尝试靠近
			else {
				if (theMaid.getNavigator().noPath() || logSpamLimiter % 10 == 0) {
					theMaid.getNavigator().tryMoveToXYZ(targetToProcess.posX, targetToProcess.posY, targetToProcess.posZ, moveSpeed * 1.15F);
				}
			}
			return; 
		}

		// =======================================================
		// 👇 正常健康状态下的逻辑 👇
		// =======================================================

		if (rescueBerserkCooldown > 0) rescueBerserkCooldown--;

		if (rescueBerserkTimer > 0) {
			rescueBerserkTimer--;
			if (rescueBerserkTimer <= 0) {
				rescueBerserkCooldown = 200;
				this.setMaidOverDrive(0);
			}
		}

		if (rescueBerserkCooldown <= 0 && rescueBerserkTimer <= 0) {
			Entity rawOwner = theMaid.getMaidMasterEntity();
			if (rawOwner instanceof EntityLivingBase) {
				EntityLivingBase owner = (EntityLivingBase) rawOwner;
				if (owner.getRevengeTarget() == entityTarget && theMaid.getDistanceSq(entityTarget) >= 100.0D) { 
					rescueBerserkTimer = 200; 
					this.setMaidOverDrive(200); 
					theMaid.playLittleMaidVoiceSound(EnumSound.FIND_TARGET_B, true); 
				}
			}
		}

		if (this.rescueBerserkTimer > 0) {
			theMaid.setBloodsuck(true); 
		}

		// 🌟 终极跳劈状态机 
		if (this.isJumpSlashing) {
			this.jumpSlashTimer++;
			theMaid.fallDistance = 0.0F;
			
			if (theMaid.collidedHorizontally) {
				theMaid.motionX = 0.0D;
				theMaid.motionZ = 0.0D;
			}
			
			if (this.jumpSlashTimer <= 10 && theMaid.motionY > 0.05D) {
				if (!theMaid.collidedHorizontally) {
					theMaid.motionX = this.jumpDirX;
					theMaid.motionZ = this.jumpDirZ;
				}
			} 
			else {
				if (this.jumpSlashTimer == 11) {
					theMaid.swingArm(net.minecraft.util.EnumHand.MAIN_HAND);
				}
				theMaid.setNoGravity(true);
				theMaid.motionY -= 0.06D; 
				if (theMaid.motionY < -0.5D) theMaid.motionY = -0.5D; 
				
				if (!theMaid.collidedHorizontally) {
					theMaid.motionX = this.jumpDirX * 1.1D;
					theMaid.motionZ = this.jumpDirZ * 1.1D;
				}
			}

			if (theMaid.motionY <= 0.0D) {
				double distSq = theMaid.getDistanceSq(entityTarget);
				if (distSq < 9.0D || theMaid.getEntityBoundingBox().grow(0.8D).intersects(entityTarget.getEntityBoundingBox())) {
					entityTarget.hurtResistantTime = 0; 
					theMaid.attackEntityAsMob(entityTarget);
					theMaid.playSound(net.minecraft.init.SoundEvents.ENTITY_PLAYER_ATTACK_CRIT, 1.2F, 0.8F);
					if (worldObj instanceof net.minecraft.world.WorldServer) {
						((net.minecraft.world.WorldServer)worldObj).spawnParticle(net.minecraft.util.EnumParticleTypes.CRIT, entityTarget.posX, entityTarget.posY + entityTarget.height / 2.0F, entityTarget.posZ, 25, 0.5D, 0.5D, 0.5D, 0.3D);
					}
					theMaid.motionY = 0.3D;
					theMaid.motionX = -this.jumpDirX * 0.3D;
					theMaid.motionZ = -this.jumpDirZ * 0.3D;
					this.isJumpSlashing = false;
					theMaid.setNoGravity(false);
					this.actionDelayTimer = getWeaponCooldown(); 
				}
			}

			if (theMaid.onGround || this.jumpSlashTimer > 40) {
				this.isJumpSlashing = false;
				theMaid.setNoGravity(false);
				this.actionDelayTimer = (int)(getWeaponCooldown() * 0.5F);
			}
			return; 
		}

		// 🌟 突进(漩涡)状态保护锁
		if (this.isDashBuff) {
			this.dashTimer++;
			
			if (this.dashTimer > 15 || (theMaid.onGround && Math.abs(theMaid.motionX) < 0.05D && Math.abs(theMaid.motionZ) < 0.05D) || theMaid.collidedHorizontally) {
				this.isDashBuff = false;
				theMaid.hurtResistantTime = 0;
			} else {
				double dX = entityTarget.posX - theMaid.posX;
				double dZ = entityTarget.posZ - theMaid.posZ;
				double distance = Math.sqrt(dX * dX + dZ * dZ);

				theMaid.rotationYaw = (float)(Math.atan2(dZ, dX) * 180.0D / Math.PI) - 90.0F;
				theMaid.renderYawOffset = theMaid.rotationYaw;

				if (distance > 1.5D && distance < 10.0D) {
					theMaid.motionX = (dX / distance) * 0.25D; 
					theMaid.motionZ = (dZ / distance) * 0.25D;
					return; 
				} 
				else if (distance <= 1.5D || theMaid.getEntityBoundingBox().grow(0.8D).intersects(entityTarget.getEntityBoundingBox())) {
					theMaid.attackEntityAsMob(entityTarget);
					if (rescueBerserkTimer > 100) { rescueBerserkTimer = 100; this.setMaidOverDrive(100); }
					this.isDashBuff = false;
					this.actionDelayTimer = getWeaponCooldown(); 
					theMaid.motionX = 0.0D; theMaid.motionZ = 0.0D;
					theMaid.velocityChanged = true; 

					if (entityTarget instanceof EntityLivingBase) {
						((EntityLivingBase)entityTarget).knockBack(theMaid, 1.5F, (double)MathHelper.sin(theMaid.rotationYaw * 0.017453292F), (double)(-MathHelper.cos(theMaid.rotationYaw * 0.017453292F)));
						entityTarget.velocityChanged = true;
					}

					theMaid.playSound(net.minecraft.init.SoundEvents.ENTITY_PLAYER_ATTACK_CRIT, 1.0F, 1.0F);
					if (worldObj instanceof net.minecraft.world.WorldServer) ((net.minecraft.world.WorldServer)worldObj).spawnParticle(net.minecraft.util.EnumParticleTypes.CRIT, entityTarget.posX, entityTarget.posY + entityTarget.height / 2.0F, entityTarget.posZ, 15, 0.3D, 0.3D, 0.3D, 0.2D);
					return; 
				} else {
					this.isDashBuff = false;
				}
			}
		}

		// 3. FSM 动作连招预备
		boolean isFSM = pendingBackstep || pendingDash;
		if (actionDelayTimer > 0) {
			actionDelayTimer--;
			
			if (isFSM) {
				if (pendingBackstep) {
					theMaid.motionX = 0.0D; theMaid.motionZ = 0.0D; theMaid.motionY = -0.08D; 
					this.isGuard = true; 
					if (!theMaid.maidAvatar.isHandActive()) {
						theMaid.maidAvatar.setActiveHand(net.minecraft.util.EnumHand.MAIN_HAND);
					}
					theMaid.addPotionEffect(new net.minecraft.potion.PotionEffect(net.minecraft.init.MobEffects.RESISTANCE, 5, 1, false, false));
					if (worldObj instanceof net.minecraft.world.WorldServer && logSpamLimiter % 2 == 0) {
						((net.minecraft.world.WorldServer)worldObj).spawnParticle(net.minecraft.util.EnumParticleTypes.CRIT, theMaid.posX + (theMaid.getRNG().nextFloat()-0.5), theMaid.posY + 0.5D, theMaid.posZ + (theMaid.getRNG().nextFloat()-0.5), 2, 0.0D, 0.0D, 0.0D, 0.1D);
					}
				}
				if (pendingDash) {
					this.isGuard = true;
					if (!theMaid.maidAvatar.isHandActive()) {
						theMaid.maidAvatar.setActiveHand(net.minecraft.util.EnumHand.MAIN_HAND);
					}
				}
				
				if (actionDelayTimer <= 0) {
					if (pendingBackstep) {
						pendingBackstep = false; 
						boolean hasArmor = false;
						for (net.minecraft.item.ItemStack armorStack : theMaid.getArmorInventoryList()) {
							if (armorStack != null && !armorStack.isEmpty()) { hasArmor = true; break; }
						}
						if (hasArmor) theMaid.playSound(net.minecraft.init.SoundEvents.ITEM_ARMOR_EQUIP_LEATHER, 0.8F, 1.2F);
						theMaid.playLittleMaidVoiceSound(theMaid.isBloodsuck() ? EnumSound.FIND_TARGET_B : EnumSound.FIND_TARGET_N, true);
						
						double dX = theMaid.posX - entityTarget.posX; double dZ = theMaid.posZ - entityTarget.posZ; double distance = Math.sqrt(dX * dX + dZ * dZ);
						if (distance >= 0.0001D) {
							theMaid.motionX = (dX / distance) * 0.35D; 
							theMaid.motionZ = (dZ / distance) * 0.35D; 
							theMaid.motionY = 0.0D; 
							theMaid.velocityChanged = true; 
						}
						pendingDash = true; 
						actionDelayTimer = (int)(getWeaponCooldown() * 0.6F); 
					} 
					else if (pendingDash) {
						pendingDash = false;
						theMaid.playSound(net.minecraft.init.SoundEvents.ENTITY_PLAYER_ATTACK_NODAMAGE, 1.0F, 0.8F);
						this.isGuard = false; theMaid.maidAvatar.stopActiveHand(); theMaid.swingArm(net.minecraft.util.EnumHand.MAIN_HAND);
						
						this.isDashBuff = true;
						this.dashTimer = 0; 
						theMaid.velocityChanged = true; 
					}
				}
				return; 
			}
		}

		// 常规冲锋寻路与跳劈触发
		if (--rerouteTimer <= 0) {
			if (isReroute || theMaid.getEntitySenses().canSee(entityTarget)) {
				rerouteTimer = 4 + theMaid.getRNG().nextInt(7);
				double distToTarget = theMaid.getDistanceSq(entityTarget);
				float burstSpeed = moveSpeed;
				
				if (this.rescueBerserkTimer > 0) burstSpeed = moveSpeed * 1.3F; 
				else if (distToTarget < 36.0D) burstSpeed = moveSpeed * 1.15F; 
				
				theMaid.getNavigator().tryMoveToXYZ(entityTarget.posX, entityTarget.posY, entityTarget.posZ, burstSpeed);

				double dist = Math.sqrt(distToTarget);
				if (this.actionDelayTimer <= 0 && dist >= 4.0D && dist <= 8.0D && !this.isDashBuff) {
					if (theMaid.getRNG().nextInt(100) < 25) {
						this.isJumpSlashing = true;
						this.jumpSlashTimer = 0;
						theMaid.playSound(net.minecraft.init.SoundEvents.ENTITY_ENDERDRAGON_FLAP, 1.0F, 1.2F);
						theMaid.playLittleMaidVoiceSound(EnumSound.FIND_TARGET_B, true);
						
						theMaid.motionY = 0.45D; 
						double dirX = (entityTarget.posX - theMaid.posX) / dist;
						double dirZ = (entityTarget.posZ - theMaid.posZ) / dist;
						this.jumpDirX = dirX * 0.4D; this.jumpDirZ = dirZ * 0.4D;
						return; 
					}
				}
			} else {
				theMaid.setAttackTarget(null);
				theMaid.setRevengeTarget(null);
			}
		}

		// 5. 常规斩击判定
		if (this.actionDelayTimer <= 0 && !this.isJumpSlashing && !this.isDashBuff) {
			double attackRangeSq = (double)theMaid.width + (double)entityTarget.width + 0.8D;
			attackRangeSq *= attackRangeSq;

			double currentDistSq = theMaid.getDistanceSq(entityTarget.posX, entityTarget.getEntityBoundingBox().minY, entityTarget.posZ);
			
			if (currentDistSq <= attackRangeSq) {
				double tdx = entityTarget.posX - theMaid.posX;
				double tdz = entityTarget.posZ - theMaid.posZ;
				float targetYaw = (float)(Math.atan2(tdz, tdx) * 180.0D / Math.PI) - 90.0F;
				
				theMaid.rotationYaw = targetYaw;
				theMaid.rotationYawHead = targetYaw;
				theMaid.renderYawOffset = targetYaw;

				double vdx = -Math.sin(theMaid.renderYawOffset * 3.1415926535897932384626433832795F / 180F);
				double vdz = Math.cos(theMaid.renderYawOffset * 3.1415926535897932384626433832795F / 180F);
				double ld = (tdx * vdx + tdz * vdz) / (Math.sqrt(tdx * tdx + tdz * tdz) * Math.sqrt(vdx * vdx + vdz * vdz));
				
				boolean canSlashNow = (ld >= -0.35D) && theMaid.getSwingStatusDominant().canAttack();

				if (canSlashNow) {
					theMaid.attackEntityAsMob(entityTarget); 
					if (rescueBerserkTimer > 100) { rescueBerserkTimer = 100; this.setMaidOverDrive(100); }
					
					if (theMaid.onGround && !theMaid.getHeldItemMainhand().isEmpty() && theMaid.getHeldItemMainhand().getItem() instanceof net.minecraft.item.ItemSword) {
						worldObj.playSound(null, theMaid.posX, theMaid.posY, theMaid.posZ, net.minecraft.init.SoundEvents.ENTITY_PLAYER_ATTACK_SWEEP, theMaid.getSoundCategory(), 1.0F, 1.0F);
						if (worldObj instanceof net.minecraft.world.WorldServer) ((net.minecraft.world.WorldServer)worldObj).spawnParticle(net.minecraft.util.EnumParticleTypes.SWEEP_ATTACK, entityTarget.posX, entityTarget.posY + (entityTarget.height / 2.0F), entityTarget.posZ, 1, 0.0D, 0.0D, 0.0D, 0.0D);
						
						java.util.List<EntityLivingBase> list = worldObj.getEntitiesWithinAABB(EntityLivingBase.class, theMaid.getEntityBoundingBox().grow(2.0D, 1.5D, 2.0D));
						float baseAttackDamage = (float)theMaid.getEntityAttribute(net.minecraft.entity.SharedMonsterAttributes.ATTACK_DAMAGE).getAttributeValue();
						float sweepRatio = net.minecraft.enchantment.EnchantmentHelper.getSweepingDamageRatio(theMaid);
						float sweepDamage = 1.0F + (sweepRatio * baseAttackDamage);
						Entity owner = theMaid.getMaidMasterEntity();
						double yawRad = theMaid.renderYawOffset * Math.PI / 180.0D;
						double lookX = -Math.sin(yawRad); double lookZ = Math.cos(yawRad);

						for (EntityLivingBase aoeTarget : list) {
							if (aoeTarget == theMaid || aoeTarget == entityTarget || aoeTarget == owner || theMaid.isOnSameTeam(aoeTarget)) continue;
							if (theMaid.getDistanceSq(aoeTarget) >= 16.0D) continue;
							double dx = aoeTarget.posX - theMaid.posX; double dz = aoeTarget.posZ - theMaid.posZ; double distanceXY = Math.sqrt(dx * dx + dz * dz);
							if (distanceXY > 0.0001D) {
								double cosTheta = (dx * lookX + dz * lookZ) / distanceXY;
								if (cosTheta > 0.25D || distanceXY <= 1.2D) { 
									aoeTarget.knockBack(theMaid, 0.4F, (double)MathHelper.sin(theMaid.rotationYaw * 0.017453292F), (double)(-MathHelper.cos(theMaid.rotationYaw * 0.017453292F)));
									aoeTarget.attackEntityFrom(net.minecraft.util.DamageSource.causeMobDamage(theMaid), sweepDamage);
								}
							}
						}
					}
					
					this.actionDelayTimer = getWeaponCooldown(); 

					boolean isBerserk = theMaid.isBloodsuck();
					float triggerChance = isBerserk ? 0.50F : 0.25F;
					if (theMaid.getRNG().nextFloat() < triggerChance) {
						this.actionDelayTimer = (int)(getWeaponCooldown() * 0.3F); 
						this.pendingBackstep = true; 
					}
				} 
			}
		}
	} 

	@Override
	public void setEnable(boolean pFlag) { fEnable = pFlag; }

	@Override
	public boolean getEnable() { return fEnable; }
}
