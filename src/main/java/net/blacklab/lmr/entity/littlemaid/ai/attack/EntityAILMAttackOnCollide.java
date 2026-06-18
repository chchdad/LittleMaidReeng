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
 * メイドさんの直接攻撃系処理 (终极横扫剑技 + 真·底层红温超视距救驾 + 小白拉扯走位 + 零重力跳劈处决)
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

	//  狂暴距离补偿变量
	protected int rescueBerserkTimer = 0;      
	protected int rescueBerserkCooldown = 0;   

	//  绝境求生变量 (小白走位)
	protected boolean isKitingPhase = false;
	protected int dodgeCooldown = 0;
	protected boolean strafingClockwise = true; // 小白左右横移标志

	//  跳劈处决变量
	protected boolean isJumpSlashing = false;
	protected int jumpSlashTimer = 0;
	protected double jumpDirX = 0;
	protected double jumpDirZ = 0;

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
		if (actionDelayTimer > 0 || pendingBackstep || pendingDash || retreatTimer > 0 || isDashBuff || isKitingPhase || isJumpSlashing) {
			if (entityTarget != null && entityTarget.isEntityAlive() && !entityTarget.isDead) {
				return true; 
			}
		}
		Entity lentity = theMaid.getAttackTarget();
		if (lentity == null || entityTarget != lentity || entityTarget.isDead || !entityTarget.isEntityAlive()) {
			resetTask();
			return false;
		}
		if (!MaidHelper.isTargetReachable(this.theMaid, lentity, 0.0D)) {
			return false;
		}
		if (!isReroute) {
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
		retreatTimer = 0;
		actionDelayTimer = 0;
		pendingBackstep = false;
		pendingDash = false;
		isDashBuff = false;
		isKitingPhase = false;
		dodgeCooldown = 0;

		// 安全重置跳劈重力锁
		if (isJumpSlashing) {
			isJumpSlashing = false;
			theMaid.setNoGravity(false);
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

		if (entityTarget == null || entityTarget.isDead || !entityTarget.isEntityAlive()) {
			resetTask();
			return;
		}

		// 视角始终锁定目标
		theMaid.getLookHelper().setLookPositionWithEntity(entityTarget, 30F, 30F);

		// =======================================================
		//  终极跳劈状态机
		// =======================================================
		if (this.isJumpSlashing) {
			this.jumpSlashTimer++;
			
			// 1. 强制关闭原版重力，接管下落速度
			theMaid.setNoGravity(true);
			theMaid.motionY -= 0.05D; // 自定义重力：越下落越快！
			
			// 2. 赋予滞空横向惯性
			theMaid.motionX = this.jumpDirX;
			theMaid.motionZ = this.jumpDirZ;
			theMaid.velocityChanged = true;

			// 3. 下落处决判定
			if (theMaid.motionY <= 0.0D) {
				double distSq = theMaid.getDistanceSq(entityTarget);
				if (distSq < 9.0D || theMaid.getEntityBoundingBox().grow(0.8D).intersects(entityTarget.getEntityBoundingBox())) {
					// 强制清空敌人无敌帧，打出处决伤害
					entityTarget.hurtResistantTime = 0; 
					theMaid.attackEntityAsMob(entityTarget);
					
					// 播放沉闷打击音效，爆出巨量暴击粒子
					theMaid.playSound(net.minecraft.init.SoundEvents.ENTITY_PLAYER_ATTACK_CRIT, 1.2F, 0.8F);
					if (worldObj instanceof net.minecraft.world.WorldServer) {
						((net.minecraft.world.WorldServer)worldObj).spawnParticle(
							net.minecraft.util.EnumParticleTypes.CRIT, 
							entityTarget.posX, entityTarget.posY + entityTarget.height / 2.0F, entityTarget.posZ, 
							25, 0.5D, 0.5D, 0.5D, 0.3D
						);
					}

					// 斩击后坐力：向后方微弹并恢复重力
					theMaid.motionY = 0.3D;
					theMaid.motionX = -this.jumpDirX * 0.4D;
					theMaid.motionZ = -this.jumpDirZ * 0.4D;
					
					this.isJumpSlashing = false;
					theMaid.setNoGravity(false);
					this.actionDelayTimer = 15; // 落地硬直
				}
			}

			// 4. 落地或超时安全锁
			if (theMaid.onGround || this.jumpSlashTimer > 50) {
				this.isJumpSlashing = false;
				theMaid.setNoGravity(false);
			}
			return; // 滞空期间跳过后续所有 AI 逻辑
		}

		// =======================================================
		// 0. 绝境状态机与强制缴械 (10% - 20% 阈值)
		// =======================================================
		float hpPct = theMaid.getHealth() / theMaid.getMaxHealth();
		if (hpPct <= 0.10F) {
			this.isKitingPhase = true;
		} else if (hpPct >= 0.20F) {
			this.isKitingPhase = false;
		}

		if (this.dodgeCooldown > 0) this.dodgeCooldown--;

		// =======================================================
		// 1. 红温超视距救主狂暴 (受绝境锁管辖)
		// =======================================================
		if (rescueBerserkCooldown > 0) rescueBerserkCooldown--;

		if (rescueBerserkTimer > 0) {
			rescueBerserkTimer--;
			if (rescueBerserkTimer <= 0) {
				rescueBerserkCooldown = 200;
				this.setMaidOverDrive(0);
			}
		}

		if (rescueBerserkCooldown <= 0 && rescueBerserkTimer <= 0 && !this.isKitingPhase) {
			Entity rawOwner = theMaid.getMaidMasterEntity();
			if (rawOwner instanceof EntityLivingBase) {
				EntityLivingBase owner = (EntityLivingBase) rawOwner;
				if (owner.getRevengeTarget() == entityTarget) {
					if (theMaid.getDistanceSq(entityTarget) >= 100.0D) { 
						rescueBerserkTimer = 200; 
						this.setMaidOverDrive(200); 
						theMaid.playLittleMaidVoiceSound(EnumSound.FIND_TARGET_B, true); 
					}
				}
			}
		}

		// 绝境缴械
		if (this.isKitingPhase) {
			theMaid.setBloodsuck(false);
			this.rescueBerserkTimer = 0; 
			this.setMaidOverDrive(0);
		} else if (this.rescueBerserkTimer > 0) {
			theMaid.setBloodsuck(true); 
		}

		// =======================================================
		//  躲避箭矢 
		// =======================================================
		if (this.isKitingPhase && this.dodgeCooldown <= 0) {
			java.util.List<net.minecraft.entity.projectile.EntityArrow> arrows = worldObj.getEntitiesWithinAABB(net.minecraft.entity.projectile.EntityArrow.class, theMaid.getEntityBoundingBox().grow(6.0D, 3.0D, 6.0D));
			boolean incoming = false;
			for (net.minecraft.entity.projectile.EntityArrow arrow : arrows) {
				if (arrow.inGround || arrow.shootingEntity == theMaid || arrow.shootingEntity == theMaid.getMaidMasterEntity()) continue;
				if (arrow.motionX * arrow.motionX + arrow.motionY * arrow.motionY + arrow.motionZ * arrow.motionZ > 0.05D) {
					incoming = true; break;
				}
			}
			
			if (incoming) {
				double yaw = theMaid.rotationYaw * Math.PI / 180.0D;
				double strafeYaw = yaw + (theMaid.getRNG().nextBoolean() ? Math.PI / 2.0D : -Math.PI / 2.0D); 
				theMaid.motionX = -Math.sin(strafeYaw) * 1.5D;
				theMaid.motionZ = Math.cos(strafeYaw) * 1.5D;
				theMaid.motionY = 0.25D; 
				theMaid.velocityChanged = true;
				this.dodgeCooldown = 15; 
				
				theMaid.playSound(net.minecraft.init.SoundEvents.ITEM_ARMOR_EQUIP_LEATHER, 1.0F, 1.5F);
				if (worldObj instanceof net.minecraft.world.WorldServer) {
					((net.minecraft.world.WorldServer)worldObj).spawnParticle(net.minecraft.util.EnumParticleTypes.CLOUD, theMaid.posX, theMaid.posY + 0.2D, theMaid.posZ, 10, 0.3D, 0.1D, 0.3D, 0.1D);
				}
			}
		}

		// =======================================================
		//  寻路 (常规冲锋 ， 小白拉扯)
		// =======================================================
		if (--rerouteTimer <= 0) {
			if (this.isKitingPhase) {
				rerouteTimer = 5 + theMaid.getRNG().nextInt(5);
				
				// 1. 随机改变左右横移方向
				if (theMaid.getRNG().nextInt(4) == 0) {
					this.strafingClockwise = !this.strafingClockwise;
				}
				
				// 2. 计算小白走位矢量 (朝向目标的法线)
				double d0 = entityTarget.posX - theMaid.posX;
				double d1 = entityTarget.posZ - theMaid.posZ;
				double dist = Math.sqrt(d0 * d0 + d1 * d1);
				
				double dirX = d0 / dist;
				double dirZ = d1 / dist;
				
				// 获取法向量用于左右平移
				double strafeX = this.strafingClockwise ? -dirZ : dirZ;
				double strafeZ = this.strafingClockwise ? dirX : -dirX;
				
				// 3. 动态控制进退：距离小于 4 倒退，大于 7 逼近
				double forward = 0;
				if (dist < 4.0D) forward = -1.2D; 
				else if (dist > 7.0D) forward = 1.0D; 
				
				// 融合前后与左右走位
				double moveX = dirX * forward + strafeX * 0.8D;
				double moveZ = dirZ * forward + strafeZ * 0.8D;
				
				theMaid.getNavigator().tryMoveToXYZ(theMaid.posX + moveX * 3.0D, theMaid.posY, theMaid.posZ + moveZ * 3.0D, moveSpeed * 1.3F);

				//  4. 触发跳劈判定！在 4~8 格绝佳安全距离，25% 概率发起空中死斗
				if (this.actionDelayTimer <= 0 && dist >= 4.0D && dist <= 8.0D) {
					if (theMaid.getRNG().nextInt(100) < 25) {
						this.isJumpSlashing = true;
						this.jumpSlashTimer = 0;
						
						// 蓄力起跳音效与红温警告
						theMaid.playSound(net.minecraft.init.SoundEvents.ENTITY_ENDERDRAGON_FLAP, 1.0F, 1.2F);
						theMaid.playLittleMaidVoiceSound(EnumSound.FIND_TARGET_B, true);
						
						// 关闭重力，赋予起跳速度
						theMaid.setNoGravity(true);
						theMaid.motionY = 0.65D; 
						
						// 记录惯性矢量 (稍微带点预判)
						this.jumpDirX = dirX * 0.7D;
						this.jumpDirZ = dirZ * 0.7D;
						return; // 起跳直接结束本回合逻辑
					}
				}

			} 
			else if (isReroute || theMaid.getEntitySenses().canSee(entityTarget)) {
				// 常规高移速追踪
				rerouteTimer = 4 + theMaid.getRNG().nextInt(7);
				double distToTarget = theMaid.getDistanceSq(entityTarget);
				float burstSpeed = moveSpeed;
				
				if (this.rescueBerserkTimer > 0) burstSpeed = moveSpeed * 1.8F; 
				else if (distToTarget < 36.0D) burstSpeed = moveSpeed * 1.5F; 
				
				theMaid.getNavigator().tryMoveToXYZ(entityTarget.posX, entityTarget.posY, entityTarget.posZ, burstSpeed);
			} else {
				theMaid.setAttackTarget(null);
				theMaid.setRevengeTarget(null);
			}
		}

		// =======================================================
		// 2. Dash 追击系统 (绝境下自动封印)
		// =======================================================
		if (this.isDashBuff && !this.isKitingPhase) {
			if (theMaid.onGround && Math.abs(theMaid.motionX) < 0.05D && Math.abs(theMaid.motionZ) < 0.05D) {
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
					theMaid.attackEntityAsMob(entityTarget);
					if (rescueBerserkTimer > 100) { rescueBerserkTimer = 100; this.setMaidOverDrive(100); }
					this.isDashBuff = false;
					theMaid.motionX = 0.0D; theMaid.motionY = 0.0D; theMaid.motionZ = 0.0D;
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

		// =======================================================
		// 3. FSM 连招 (长蓄力 -> 物理后撤 -> 拔刀突刺) (绝境下自动封印)
		// =======================================================
		if (actionDelayTimer > 0 && !this.isKitingPhase) {
			actionDelayTimer--;
			
			if (pendingBackstep) {
				theMaid.motionX = 0.0D; theMaid.motionZ = 0.0D; theMaid.motionY = -0.08D; 
				this.isGuard = true; 
				if (!theMaid.maidAvatar.isHandActive()) theMaid.maidAvatar.setActiveHand(net.minecraft.util.EnumHand.MAIN_HAND);
				theMaid.addPotionEffect(new net.minecraft.potion.PotionEffect(net.minecraft.init.MobEffects.RESISTANCE, 5, 1, false, false));
				if (worldObj instanceof net.minecraft.world.WorldServer && logSpamLimiter % 2 == 0) ((net.minecraft.world.WorldServer)worldObj).spawnParticle(net.minecraft.util.EnumParticleTypes.CRIT, theMaid.posX + (theMaid.getRNG().nextFloat()-0.5), theMaid.posY + 0.5D, theMaid.posZ + (theMaid.getRNG().nextFloat()-0.5), 2, 0.0D, 0.0D, 0.0D, 0.1D);
			}
			
			if (pendingDash) {
				this.isGuard = true;
				if (!theMaid.maidAvatar.isHandActive()) theMaid.maidAvatar.setActiveHand(net.minecraft.util.EnumHand.MAIN_HAND);
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
						theMaid.motionX = (dX / distance) * 0.6D; theMaid.motionZ = (dZ / distance) * 0.6D; theMaid.motionY = 0.0D; theMaid.velocityChanged = true;
					}
					pendingDash = true; actionDelayTimer = 10; 
				} 
				else if (pendingDash) {
					pendingDash = false;
					theMaid.playSound(net.minecraft.init.SoundEvents.ENTITY_PLAYER_ATTACK_NODAMAGE, 1.0F, 0.8F);
					this.isGuard = false; theMaid.maidAvatar.stopActiveHand(); theMaid.swingArm(net.minecraft.util.EnumHand.MAIN_HAND);
					double dX = entityTarget.posX - theMaid.posX; double dZ = entityTarget.posZ - theMaid.posZ; double distance = Math.sqrt(dX * dX + dZ * dZ);
					if (distance >= 0.0001D) {
						theMaid.motionX = (dX / distance) * 1.2D;  theMaid.motionZ = (dZ / distance) * 1.2D; theMaid.motionY = 0.2D; theMaid.velocityChanged = true;
					}
					theMaid.hurtResistantTime = 20; this.isDashBuff = true; 
				}
			}
			return; 
		}

		// =======================================================
		// 5. 斩击判定 (瞬转 + 严格攻速 + 剑刃横扫)
		// =======================================================
		double attackRangeSq = (double)theMaid.width + (double)entityTarget.width + 0.8D;
		attackRangeSq *= attackRangeSq;
		
		// 绝境边缘试探
		if (this.isKitingPhase) attackRangeSq = Math.max(attackRangeSq, 9.0D);

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
				
				if (!this.isKitingPhase) {
					boolean isBerserk = theMaid.isBloodsuck();
					float triggerChance = isBerserk ? 0.50F : 0.25F;
					if (theMaid.getRNG().nextFloat() < triggerChance) {
						this.actionDelayTimer = isBerserk ? 20 : 25; 
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
