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

/**
 * メイドさんの直接攻撃系処理 (完美接口劫持：拉响警报并让出控制权)
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
			System.out.println("[LMR-ATTACK-DEBUG] 反射修改红温状态失败: " + e.getMessage());
		}
	}

	private int getWeaponCooldown() {
		float attackSpeed = 4.0F; 
		net.minecraft.entity.ai.attributes.IAttributeInstance speedAttr = theMaid.getEntityAttribute(net.minecraft.entity.SharedMonsterAttributes.ATTACK_SPEED);
		if (speedAttr != null) {
			attackSpeed = (float) speedAttr.getAttributeValue();
		}
		return (int)(20.0F / Math.max(0.1F, attackSpeed));
	}

	@Override
	public boolean shouldExecute() {
		// 🌟 核心拦截：如果正处于躲避爆炸警报期间，绝对不抢夺身体控制权！
		if (theMaid.isForcedToAvoidExplosion()) {
			return false;
		}

		EntityLivingBase lentity = theMaid.getAttackTarget();
		
		if (this.isKitingPhase && this.threatTarget != null && this.threatTarget.isEntityAlive()) {
			lentity = this.threatTarget;
		}

		if (!fEnable || theMaid.isMaidWait() || lentity == null) return false;
		
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
		EntityLivingBase lentity = this.isKitingPhase ? this.threatTarget : theMaid.getAttackTarget();
		if(lentity != null && !lentity.isDead){
			theMaid.playLittleMaidVoiceSound(theMaid.isBloodsuck() ? EnumSound.FIND_TARGET_B : EnumSound.FIND_TARGET_N, true);
		}
		
		if (!this.isKitingPhase) {
			theMaid.getNavigator().setPath(pathToTarget, moveSpeed);
		}
		rerouteTimer = 0;
		theMaid.maidAvatar.stopActiveHand();
	}

	@Override
	public boolean shouldContinueExecuting() {
		// 🌟 核心拦截：一旦拉响警报，立马返回 false 让出控制权！
		if (theMaid.isForcedToAvoidExplosion()) {
			return false;
		}

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
		
		// 🌟 注意：如果是因为逃跑警报触发的 reset，我们绝对不能清空黑名单！
		if (isKitingPhase && !theMaid.isForcedToAvoidExplosion()) {
			isKitingPhase = false;
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

		// =======================================================
		// 0. 绝境雷达进入与退出 (10% - 20% 阈值)
		// =======================================================
		float hpPct = theMaid.getHealth() / theMaid.getMaxHealth();
		if (hpPct <= 0.10F) {
			if (!this.isKitingPhase) {
				this.isKitingPhase = true;
				this.threatTarget = entityTarget; 
				theMaid.setAttackTarget(null);    
				theMaid.setRevengeTarget(null);

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
		} else if (hpPct >= 0.20F) {
			if (this.isKitingPhase) {
				this.isKitingPhase = false;
				if (this.threatTarget != null && this.threatTarget.isEntityAlive()) {
					theMaid.setAttackTarget(this.threatTarget);
				}
				this.threatTarget = null;
			}
		}

		// =======================================================
		// 🌟 1. 绝境专属：触发远程警报，劫持底层 AI
		// =======================================================
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
				
				// 被打后强制按遥控器：强制躲避 40 Tick
				theMaid.setForceAvoidExplosion(40);
				this.actionDelayTimer = getWeaponCooldown(); 
				return; 
			}

			if (theMaid.hurtTime <= 0 && theMaid.maidAvatar.isHandActive()) {
				theMaid.maidAvatar.stopActiveHand();
			}

			double distSq = theMaid.getDistanceSq(targetToProcess);
			
			boolean isChasedByMonster = (targetToProcess.getRevengeTarget() == theMaid);
			if (targetToProcess instanceof EntityLiving) {
				isChasedByMonster = isChasedByMonster || (((EntityLiving)targetToProcess).getAttackTarget() == theMaid);
			}

			// 🌟 核心判断：距离小于4格 或 武器在冷却
			boolean tooCloseToThreat = isChasedByMonster && (distSq < 16.0D);

			if (this.actionDelayTimer > 0 || tooCloseToThreat) {
				// 🚨 按下遥控器！通知底层 AI 接管逃命，并直接中断当前帧逻辑！
				theMaid.setForceAvoidExplosion(40);
				if (logSpamLimiter % 10 == 0) System.out.println("[LMR-KITE] 距离过近或CD中，拉响防爆警报交出控制权！");
				return; 
			} 
			else {
				// 💚 安全期：可以反打，或者回防主人
				EntityLivingBase master = (EntityLivingBase) theMaid.getMaidMasterEntity();
				boolean needsToReturnToMaster = false;
				if (master != null && theMaid.getDistanceSq(master) > 100.0D) { 
					needsToReturnToMaster = true; 
				}

				if (needsToReturnToMaster) {
					if (theMaid.getNavigator().noPath() || logSpamLimiter % 10 == 0) {
						theMaid.getNavigator().tryMoveToEntityLiving(master, moveSpeed * 1.15F);
					}
				} else {
					double survivalMaxReach = 3.0D + (double)targetToProcess.width / 2.0D;
					double atkRangeSq = survivalMaxReach * survivalMaxReach;
					
					if (distSq <= atkRangeSq) {
						double tdx = targetToProcess.posX - theMaid.posX;
						double tdz = targetToProcess.posZ - theMaid.posZ;
						float targetYaw = (float)(Math.atan2(tdz, tdx) * 180.0D / Math.PI) - 90.0F;
						float yawDiff = MathHelper.wrapDegrees(theMaid.rotationYawHead - targetYaw);
						
						theMaid.rotationYaw = targetYaw;
						theMaid.rotationYawHead = targetYaw;
						theMaid.renderYawOffset = targetYaw;

						if (Math.abs(yawDiff) <= 55.0F) {
							theMaid.attackEntityAsMob(targetToProcess);
							theMaid.swingArm(net.minecraft.util.EnumHand.MAIN_HAND);
							
							if (theMaid.onGround && !theMaid.getHeldItemMainhand().isEmpty() && theMaid.getHeldItemMainhand().getItem() instanceof net.minecraft.item.ItemSword) {
								worldObj.playSound(null, theMaid.posX, theMaid.posY, theMaid.posZ, net.minecraft.init.SoundEvents.ENTITY_PLAYER_ATTACK_SWEEP, theMaid.getSoundCategory(), 1.0F, 1.0F);
								if (worldObj instanceof net.minecraft.world.WorldServer) ((net.minecraft.world.WorldServer)worldObj).spawnParticle(net.minecraft.util.EnumParticleTypes.SWEEP_ATTACK, targetToProcess.posX, targetToProcess.posY + (targetToProcess.height / 2.0F), targetToProcess.posZ, 1, 0.0D, 0.0D, 0.0D, 0.0D);
							}
							
							// 🌟 砍完这一刀，进入 CD，下一帧自动拉响逃命警报
							this.actionDelayTimer = getWeaponCooldown(); 
						}
					} else {
						if (theMaid.getNavigator().noPath() || logSpamLimiter % 10 == 0) {
							theMaid.getNavigator().tryMoveToXYZ(targetToProcess.posX, targetToProcess.posY, targetToProcess.posZ, moveSpeed * 1.15F);
						}
					}
				}
			}
			return; 
		}

		// =======================================================
		// 👇 以下为正常健康状态下的逻辑，绝境(拉扯)状态绝对进不来这里 👇
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
					theMaid.motionX = (dX / distance) * 0.35D;
					theMaid.motionZ = (dZ / distance) * 0.35D;
					theMaid.velocityChanged = true;
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
