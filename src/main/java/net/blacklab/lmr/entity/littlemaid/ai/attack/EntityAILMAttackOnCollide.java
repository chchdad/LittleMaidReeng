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
 * メイドさんの直接攻撃系処理 (完美实装：90/80/60/50/25/10 动态血线阶梯状态机 + 纯逻辑护盾)
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
	
	// 原版状态机变量
	protected int retreatTimer = 0; 
	protected int actionDelayTimer = 0; 
	protected boolean pendingBackstep = false; 
	protected boolean pendingDash = false; 
	protected boolean isDashBuff = false; 
	protected int dashTimer = 0; 
	public boolean isGuard;
	
	// 原版护主狂暴相关
	protected int rescueBerserkTimer = 0;      
	protected int rescueBerserkCooldown = 0;   

	// 🏹 箭矢闪避冷却
	protected int dodgeCooldown = 0;
	
	// 🛡️ 护盾抵消伤害用：记录上一帧血量
	protected float lastTickHealth = -1.0F;

	// 🩸 咱们的终极动态血线状态机
	protected boolean isDynamicBerserk = false; // 是否处于 50% 连招概率翻倍的狂暴中
	protected boolean hasBerserkPerm = true;    // 是否拥有降到50%触发狂暴的许可
	protected boolean hitTenPercent = false;    // 濒死标记：是否经历过10%以下血量

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

	@Override
	public boolean shouldExecute() {
		EntityLivingBase lentity = theMaid.getAttackTarget();
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
		EntityLivingBase lentity = theMaid.getAttackTarget();
		if(lentity != null && !lentity.isDead){
			theMaid.playLittleMaidVoiceSound(theMaid.isBloodsuck() ? EnumSound.FIND_TARGET_B : EnumSound.FIND_TARGET_N, true);
		}
		
		if (pathToTarget != null) {
			theMaid.getNavigator().setPath(pathToTarget, moveSpeed);
		} else {
			theMaid.getNavigator().clearPath();
		}
		rerouteTimer = 0;
		theMaid.maidAvatar.stopActiveHand();
		this.lastTickHealth = theMaid.getHealth(); 
	}

	@Override
	public boolean shouldContinueExecuting() {
		if (actionDelayTimer > 0 || pendingBackstep || pendingDash || retreatTimer > 0 || isDashBuff) {
			if (entityTarget != null && entityTarget.isEntityAlive() && !entityTarget.isDead) {
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
		if (lentity == null || entityTarget != lentity || entityTarget.isDead || !entityTarget.isEntityAlive()) {
			resetTask();
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
		actionDelayTimer = 0;
		pendingBackstep = false;
		pendingDash = false;
		isDashBuff = false;
		
		if (rescueBerserkTimer > 0) {
			rescueBerserkTimer = 0;
			rescueBerserkCooldown = 200;
			this.setMaidOverDrive(0);
		}
	}

	@Override
	public void updateTask() {
		if (entityTarget == null || entityTarget.isDead || !entityTarget.isEntityAlive()) {
			resetTask();
			return;
		}
		
		if (this.lastTickHealth < 0) this.lastTickHealth = theMaid.getHealth();

		theMaid.getLookHelper().setLookPositionWithEntity(entityTarget, 30F, 30F);
		
		// =========================================================
		// 🩸 核心：动态血线阶梯状态机 (施密特触发器逻辑)
		// =========================================================
		float hpPct = theMaid.getHealth() / theMaid.getMaxHealth();

		// 1. 记录极限低血量标记
		if (hpPct <= 0.10F) {
			this.hitTenPercent = true;
		}

		// 2. 狂暴状态的解除条件
		if (this.isDynamicBerserk) {
			if (hpPct <= 0.25F) {
				// 降到25时，强制解除狂暴，回归防守
				this.isDynamicBerserk = false;
			} else if (hpPct >= 0.60F) {
				// 没降到25就回血了，回到60之后正常解除狂暴
				this.isDynamicBerserk = false;
			}
		}

		// 3. 狂暴许可的获取条件
		if (!this.hasBerserkPerm && !this.isDynamicBerserk) {
			if (this.hitTenPercent && hpPct >= 0.80F) {
				// 从10以下死里逃生回上来的，只需要80就给许可
				this.hasBerserkPerm = true;
				this.hitTenPercent = false; // 消耗掉该次濒死标记
			} else if (!this.hitTenPercent && hpPct >= 0.90F) {
				// 正常情况，回到90才有狂暴许可
				this.hasBerserkPerm = true;
			}
		}

		// 4. 狂暴状态的触发条件
		if (this.hasBerserkPerm && !this.isDynamicBerserk) {
			if (hpPct <= 0.50F && hpPct > 0.25F) {
				// 拥有许可，且血量降到50以下(但不能低于25)，正式进入连招狂暴！
				this.isDynamicBerserk = true;
				this.hasBerserkPerm = false; // 触发后消耗掉本次许可
			}
		}

		// =========================================================
		// 🏹 闪避神技：防箭矢侧滑
		// =========================================================
		if (this.dodgeCooldown > 0) this.dodgeCooldown--;

		if (this.dodgeCooldown <= 0) {
			java.util.List<Entity> projectiles = worldObj.getEntitiesWithinAABBExcludingEntity(theMaid, theMaid.getEntityBoundingBox().grow(6.0D, 2.0D, 6.0D));
			for (Entity proj : projectiles) {
				if (proj instanceof net.minecraft.entity.projectile.EntityArrow || 
					proj instanceof net.minecraft.entity.projectile.EntityThrowable || 
					proj instanceof net.minecraft.entity.projectile.EntityFireball) {
					
					if (proj.motionX * proj.motionX + proj.motionZ * proj.motionZ > 0.05D) {
						double dodgeDirX = -proj.motionZ;
						double dodgeDirZ = proj.motionX;
						
						double len = Math.sqrt(dodgeDirX * dodgeDirX + dodgeDirZ * dodgeDirZ);
						if (len > 0.001D) {
							dodgeDirX /= len;
							dodgeDirZ /= len;
							
							if (theMaid.getRNG().nextBoolean()) {
								dodgeDirX = -dodgeDirX;
								dodgeDirZ = -dodgeDirZ;
							}
							
							theMaid.motionX = dodgeDirX * 0.6D;
							theMaid.motionZ = dodgeDirZ * 0.6D;
							if (theMaid.onGround) {
								theMaid.motionY = 0.25D; 
							}
							
							this.dodgeCooldown = 40 + theMaid.getRNG().nextInt(20); 
							break; 
						}
					}
				}
			}
		}

		// =======================================================
		// 👇 纯正血战状态机
		// =======================================================

		if (rescueBerserkCooldown > 0) rescueBerserkCooldown--;

		if (rescueBerserkTimer > 0) {
			rescueBerserkTimer--;
			if (rescueBerserkTimer <= 0) {
				rescueBerserkCooldown = 200;
				this.setMaidOverDrive(0);
			}
		}

		// 原版机制：主人挨打触发的护主狂暴 (与血量状态机互不干涉)
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

		// 🌟 剑士唯一指定暴击大招：燕返突进
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
					this.lastTickHealth = theMaid.getHealth();
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
						// 附带极强的物理击退
						((EntityLivingBase)entityTarget).knockBack(theMaid, 1.5F, (double)MathHelper.sin(theMaid.rotationYaw * 0.017453292F), (double)(-MathHelper.cos(theMaid.rotationYaw * 0.017453292F)));
						entityTarget.velocityChanged = true;
					}

					// 唯一保留的暴击音效与粒子
					theMaid.playSound(net.minecraft.init.SoundEvents.ENTITY_PLAYER_ATTACK_CRIT, 1.0F, 1.0F);
					if (worldObj instanceof net.minecraft.world.WorldServer) ((net.minecraft.world.WorldServer)worldObj).spawnParticle(net.minecraft.util.EnumParticleTypes.CRIT, entityTarget.posX, entityTarget.posY + entityTarget.height / 2.0F, entityTarget.posZ, 15, 0.3D, 0.3D, 0.3D, 0.2D);
					this.lastTickHealth = theMaid.getHealth();
					return; 
				} else {
					this.isDashBuff = false;
				}
			}
		}

		// =======================================================
		// 🛡️ FSM 动作连招预备 (包含完美的强制锁头与抵消判定)
		// =======================================================
		boolean isFSM = pendingBackstep || pendingDash;
		if (actionDelayTimer > 0) {
			actionDelayTimer--;
			
			if (isFSM) {
				// 🌟 强行掰回头与身体：绝对不用后背去接怪物的刀！
				double forceLookX = entityTarget.posX - theMaid.posX;
				double forceLookZ = entityTarget.posZ - theMaid.posZ;
				float strictTargetYaw = (float)(Math.atan2(forceLookZ, forceLookX) * 180.0D / Math.PI) - 90.0F;
				theMaid.rotationYaw = strictTargetYaw;
				theMaid.rotationYawHead = strictTargetYaw;
				theMaid.renderYawOffset = strictTargetYaw;
				
				if (pendingBackstep) {
					theMaid.motionX = 0.0D; theMaid.motionZ = 0.0D; theMaid.motionY = -0.08D; 
					this.isGuard = true; 
					if (!theMaid.maidAvatar.isHandActive()) {
						theMaid.maidAvatar.setActiveHand(net.minecraft.util.EnumHand.MAIN_HAND);
					}
				}
				if (pendingDash) {
					this.isGuard = true;
					if (!theMaid.maidAvatar.isHandActive()) {
						theMaid.maidAvatar.setActiveHand(net.minecraft.util.EnumHand.MAIN_HAND);
					}
				}
				
				// 🌟 物理硬核防御逻辑 (无药水)
				if (this.isGuard) {
					// 1. 模拟抵挡：掉多少血，就在受伤这帧瞬间回多少血！
					if (theMaid.hurtTime == 10 && this.lastTickHealth > theMaid.getHealth()) {
						float damageTaken = this.lastTickHealth - theMaid.getHealth();
						theMaid.heal(damageTaken); 
						theMaid.playSound(net.minecraft.init.SoundEvents.ITEM_SHIELD_BLOCK, 1.0F, 0.8F + theMaid.getRNG().nextFloat() * 0.4F);
					}
					
					// 2. 致命伤强行锁 1 滴血 (硬核抢救)
					if (theMaid.getHealth() <= 1.0F) {
						theMaid.setHealth(1.0F);
						if (theMaid.isDead) {
							theMaid.isDead = false;
							theMaid.deathTime = 0;
						}
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
						this.isGuard = false; 
						theMaid.maidAvatar.stopActiveHand(); 
						theMaid.swingArm(net.minecraft.util.EnumHand.MAIN_HAND);
						
						this.isDashBuff = true;
						this.dashTimer = 0; 
						theMaid.velocityChanged = true; 
					}
				}
				this.lastTickHealth = theMaid.getHealth();
				return; 
			}
		}

		// 常规冲锋寻路 (彻底移除跳劈)
		if (--rerouteTimer <= 0) {
			if (isReroute || theMaid.getEntitySenses().canSee(entityTarget)) {
				rerouteTimer = 4 + theMaid.getRNG().nextInt(7);
				double distToTarget = theMaid.getDistanceSq(entityTarget);
				float burstSpeed = moveSpeed;
				
				if (this.rescueBerserkTimer > 0) burstSpeed = moveSpeed * 1.3F; 
				else if (distToTarget < 36.0D) burstSpeed = moveSpeed * 1.15F; 
				
				theMaid.getNavigator().tryMoveToXYZ(entityTarget.posX, entityTarget.posY, entityTarget.posZ, burstSpeed);
			} else {
				theMaid.setAttackTarget(null);
				theMaid.setRevengeTarget(null);
			}
		}

		// 5. 常规斩击判定
		if (this.actionDelayTimer <= 0 && !this.isDashBuff) {
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

					boolean isOwnerBerserk = theMaid.isBloodsuck();
					// 🌟 核心：普通剑士才会触发唯一格挡连招大招！
					if (!isOwnerBerserk) {
						// 🌟 动态血线状态机接管：狂暴状态下触发概率翻倍至 50%
						float triggerChance = this.isDynamicBerserk ? 0.50F : 0.25F;
						if (theMaid.getRNG().nextFloat() < triggerChance) {
							this.actionDelayTimer = (int)(getWeaponCooldown() * 0.3F); 
							this.pendingBackstep = true; 
						}
					}
				} 
			}
		}
		
		// 更新帧血量记录
		this.lastTickHealth = theMaid.getHealth();
	} 

	@Override
	public void setEnable(boolean pFlag) { fEnable = pFlag; }

	@Override
	public boolean getEnable() { return fEnable; }
}
