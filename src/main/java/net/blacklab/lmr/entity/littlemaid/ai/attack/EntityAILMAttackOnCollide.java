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
 * メイドさんの直接攻撃系処理 (终极横扫剑技 + 真·底层红温超视距救驾 + 蓄力滑步增强)
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

	//  狂暴距离补偿核心变量
	protected int rescueBerserkTimer = 0;      
	protected int rescueBerserkCooldown = 0;   

	private int logSpamLimiter = 0;

	public EntityAILMAttackOnCollide(EntityLittleMaid par1EntityLittleMaid, float par2, boolean par3) {
		theMaid = par1EntityLittleMaid;
		worldObj = par1EntityLittleMaid.getEntityWorld();
		moveSpeed = par2;
		isReroute = par3;
		isGuard = false;
		setMutexBits(3);
	}

	// 绕过 protected 锁修改红温数值
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
		if (actionDelayTimer > 0 || pendingBackstep || pendingDash || retreatTimer > 0 || isDashBuff) {
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

		theMaid.getLookHelper().setLookPositionWithEntity(entityTarget, 30F, 30F);

		// =======================================================
		// 1. 红温超视距救主狂暴(距离补偿 + 10秒倒计时)
		// =======================================================
		if (rescueBerserkCooldown > 0) {
			rescueBerserkCooldown--;
		}

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
				boolean isAttackingOwner = (owner.getRevengeTarget() == entityTarget);
				
				if (isAttackingOwner) {
					double distSq = theMaid.getDistanceSq(entityTarget);
					if (distSq >= 100.0D) { 
						rescueBerserkTimer = 200; 
						this.setMaidOverDrive(200); 
						theMaid.playLittleMaidVoiceSound(EnumSound.FIND_TARGET_B, true); 
					}
				}
			}
		}

		boolean isHealthBerserk = theMaid.getHealth() <= theMaid.getMaxHealth() * 0.50F;
		boolean isRescueBerserk = this.rescueBerserkTimer > 0;
		boolean isBerserk = isHealthBerserk || isRescueBerserk;
		theMaid.setBloodsuck(isBerserk); 
		
		// =======================================================
		// 2. Dash 追击系统
		// =======================================================
		if (this.isDashBuff) {
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
					
					if (rescueBerserkTimer > 100) {
						rescueBerserkTimer = 100;
						this.setMaidOverDrive(100); 
					}

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
					this.isDashBuff = false;
				}
			}
		}

		// =======================================================
		// 3. FSM 连招 (长蓄力 -> 物理后撤 -> 拔刀突刺)
		// =======================================================
		if (actionDelayTimer > 0) {
			actionDelayTimer--;
			
			if (pendingBackstep) {
				theMaid.motionX = 0.0D;
				theMaid.motionZ = 0.0D;
				theMaid.motionY = -0.08D; 
				
				this.isGuard = true; 
				if (!theMaid.maidAvatar.isHandActive()) {
					theMaid.maidAvatar.setActiveHand(net.minecraft.util.EnumHand.MAIN_HAND);
				}
				
				theMaid.addPotionEffect(new net.minecraft.potion.PotionEffect(net.minecraft.init.MobEffects.RESISTANCE, 5, 1, false, false));
				
				if (worldObj instanceof net.minecraft.world.WorldServer && logSpamLimiter % 2 == 0) {
					((net.minecraft.world.WorldServer)worldObj).spawnParticle(
						net.minecraft.util.EnumParticleTypes.CRIT, //  充满攻击性的暴击粒子
						theMaid.posX + (theMaid.getRNG().nextFloat()-0.5), 
						theMaid.posY + 0.5D, 
						theMaid.posZ + (theMaid.getRNG().nextFloat()-0.5), 
						2, 0.0D, 0.0D, 0.0D, 0.1D
					);
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
					
					//  护甲安全检测 (防空指针)
					boolean hasArmor = false;
					for (net.minecraft.item.ItemStack armorStack : theMaid.getArmorInventoryList()) {
						if (armorStack != null && !armorStack.isEmpty()) {
							hasArmor = true;
							break;
						}
					}
					
					// 如果穿了装备，叠加皮甲摩擦声
					if (hasArmor) {
						theMaid.playSound(net.minecraft.init.SoundEvents.ITEM_ARMOR_EQUIP_LEATHER, 0.8F, 1.2F);
					}
					
					//  无论是否穿甲，必定播放女仆的 Target 锁定音效
					theMaid.playLittleMaidVoiceSound(theMaid.isBloodsuck() ? EnumSound.FIND_TARGET_B : EnumSound.FIND_TARGET_N, true);
					
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
					actionDelayTimer = 10; 
				} 
				else if (pendingDash) {
					pendingDash = false;
					
					//  拔刀突刺，模拟挥剑落空短促破空的“呼”声
					theMaid.playSound(net.minecraft.init.SoundEvents.ENTITY_PLAYER_ATTACK_NODAMAGE, 1.0F, 0.8F);
					
					this.isGuard = false;
					theMaid.maidAvatar.stopActiveHand(); 
					theMaid.swingArm(net.minecraft.util.EnumHand.MAIN_HAND);
					
					double dX = entityTarget.posX - theMaid.posX;
					double dZ = entityTarget.posZ - theMaid.posZ;
					double distance = Math.sqrt(dX * dX + dZ * dZ);
					
					if (distance >= 0.0001D) {
						theMaid.motionX = (dX / distance) * 1.2D; 
						theMaid.motionZ = (dZ / distance) * 1.2D;
						theMaid.motionY = 0.2D; 
						theMaid.velocityChanged = true;
					}
					
					theMaid.hurtResistantTime = 20; 
					this.isDashBuff = true; 
				}
			}
			return; 
		}


		// =======================================================
		// 4. 寻路 (狂暴的高额移速)
		// =======================================================
		if (--rerouteTimer <= 0) {
			if (isReroute || theMaid.getEntitySenses().canSee(entityTarget)) {
				rerouteTimer = 4 + theMaid.getRNG().nextInt(7);
				
				double distToTarget = theMaid.getDistanceSq(entityTarget);
				float burstSpeed = moveSpeed;
				
				if (isRescueBerserk) {
					burstSpeed = moveSpeed * 1.8F; 
				} else if (distToTarget < 36.0D) {
					burstSpeed = moveSpeed * 1.5F; 
				}
				
				theMaid.getNavigator().tryMoveToXYZ(entityTarget.posX, entityTarget.posY, entityTarget.posZ, burstSpeed);
			} else {
				theMaid.setAttackTarget(null);
				theMaid.setRevengeTarget(null);
			}
		}

		// =======================================================
		// 5. 斩击判定 (瞬转 + 严格攻速 + 剑刃横扫)
		// =======================================================
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
				
				if (rescueBerserkTimer > 100) {
					rescueBerserkTimer = 100;
					this.setMaidOverDrive(100); 
				}
				
				if (theMaid.onGround && !theMaid.getHeldItemMainhand().isEmpty() && theMaid.getHeldItemMainhand().getItem() instanceof net.minecraft.item.ItemSword) {
					worldObj.playSound(null, theMaid.posX, theMaid.posY, theMaid.posZ, 
						net.minecraft.init.SoundEvents.ENTITY_PLAYER_ATTACK_SWEEP, 
						theMaid.getSoundCategory(), 1.0F, 1.0F);
						
					if (worldObj instanceof net.minecraft.world.WorldServer) {
						((net.minecraft.world.WorldServer)worldObj).spawnParticle(
							net.minecraft.util.EnumParticleTypes.SWEEP_ATTACK, 
							entityTarget.posX, entityTarget.posY + (entityTarget.height / 2.0F), entityTarget.posZ, 
							1, 0.0D, 0.0D, 0.0D, 0.0D
						);
					}
					
					java.util.List<EntityLivingBase> list = worldObj.getEntitiesWithinAABB(
						EntityLivingBase.class, 
						theMaid.getEntityBoundingBox().grow(2.0D, 1.5D, 2.0D)
					);
					
					float baseAttackDamage = (float)theMaid.getEntityAttribute(net.minecraft.entity.SharedMonsterAttributes.ATTACK_DAMAGE).getAttributeValue();
					float sweepRatio = net.minecraft.enchantment.EnchantmentHelper.getSweepingDamageRatio(theMaid);
					float sweepDamage = 1.0F + (sweepRatio * baseAttackDamage);
					
					double yawRad = theMaid.renderYawOffset * Math.PI / 180.0D;
					double lookX = -Math.sin(yawRad);
					double lookZ = Math.cos(yawRad);
					
					Entity owner = theMaid.getMaidMasterEntity();

					for (EntityLivingBase aoeTarget : list) {
						if (aoeTarget == theMaid || aoeTarget == entityTarget || aoeTarget == owner || theMaid.isOnSameTeam(aoeTarget)) {
							continue;
						}
						
						double distSq = theMaid.getDistanceSq(aoeTarget);
						if (distSq >= 16.0D) {
							continue;
						}
							
						double dx = aoeTarget.posX - theMaid.posX;
						double dz = aoeTarget.posZ - theMaid.posZ;
						double distanceXY = Math.sqrt(dx * dx + dz * dz);
						
						if (distanceXY > 0.0001D) {
							double cosTheta = (dx * lookX + dz * lookZ) / distanceXY;
							if (cosTheta > 0.25D || distanceXY <= 1.2D) { 
								aoeTarget.knockBack(theMaid, 0.4F, (double)MathHelper.sin(theMaid.rotationYaw * 0.017453292F), (double)(-MathHelper.cos(theMaid.rotationYaw * 0.017453292F)));
								aoeTarget.attackEntityFrom(net.minecraft.util.DamageSource.causeMobDamage(theMaid), sweepDamage);
							}
						}
					}
				}
				
				float triggerChance = isBerserk ? 0.50F : 0.25F;
				if (theMaid.getRNG().nextFloat() < triggerChance) {
					this.actionDelayTimer = isBerserk ? 20 : 25; 
					this.pendingBackstep = true; 
				}
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
