package net.blacklab.lmr.entity.littlemaid.ai.attack;

import firis.lmlib.api.constant.EnumSound;
import net.blacklab.lmr.entity.littlemaid.EntityLittleMaid;
import net.blacklab.lmr.entity.littlemaid.ai.IEntityAILM;
import net.blacklab.lmr.util.helper.MaidHelper;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.entity.ai.EntityAIBase;
import net.minecraft.item.ItemAxe;
import net.minecraft.item.ItemStack;
import net.minecraft.pathfinding.Path;
import net.minecraft.util.math.MathHelper;

/**
 * 狂战士独立 AI (狂暴全局霸体 + 统一状态机优化版)
 */
public class EntityAILMAttackBerserker extends EntityAIBase implements IEntityAILM {

	protected boolean fEnable;
	protected EntityLittleMaid theMaid;
	protected EntityLivingBase entityTarget; 
	protected float moveSpeed;
	protected boolean isReroute;
	protected Path pathToTarget;
	protected int rerouteTimer;
	protected double attackRange;

	protected int actionDelayTimer = 0; 
	protected boolean pendingDash = false; 
	protected boolean isDashBuff = false; 
	protected float lastTickHealth = -1.0F;
	protected boolean pendingOffhandStrike = false;
	protected int comboDelayTimer = 0;
	protected float storedOffhandDamage = 0.0F;
	protected long nextDashTime = 0L;

	protected int soundDelayTimer = 0;

	// 猩红连斩状态机
	protected boolean hasBerserkPerm = true;
	protected boolean isFrenzy = false;
	protected int frenzyTimer = 0;
	protected int slashCount = 0;
	protected float comboDamageBonus = 0.0F;
	protected float comboDefBonus = 0.0F;
	protected EntityLivingBase frenzyTarget = null;
	protected boolean isSingleTarget = false;
	protected boolean killClaimed = false;
	
	protected int healthLockTimer = 0; 

	protected static final java.util.UUID JUGGERNAUT_KB_UUID = java.util.UUID.fromString("8b7042a9-7fa1-4e4b-91d1-12f5a2b53f66");
	protected static final net.minecraft.entity.ai.attributes.AttributeModifier JUGGERNAUT_KB_RESIST = new net.minecraft.entity.ai.attributes.AttributeModifier(JUGGERNAUT_KB_UUID, "Juggernaut KB Resist", 1.0D, 0).setSaved(false);

	public EntityAILMAttackBerserker(EntityLittleMaid par1EntityLittleMaid) {
		theMaid = par1EntityLittleMaid;
		moveSpeed = 1.0F;
		isReroute = true;
		setMutexBits(3); 
	}

	private int getWeaponCooldown() {
		float attackSpeed = 4.0F; 
		net.minecraft.entity.ai.attributes.IAttributeInstance speedAttr = theMaid.getEntityAttribute(SharedMonsterAttributes.ATTACK_SPEED);
		if (speedAttr != null) {
			attackSpeed = (float) speedAttr.getAttributeValue();
		}
		return (int)(20.0F / Math.max(0.1F, attackSpeed));
	}

	private void setJuggernaut(boolean active) {
		net.minecraft.entity.ai.attributes.IAttributeInstance kbAttr = theMaid.getEntityAttribute(SharedMonsterAttributes.KNOCKBACK_RESISTANCE);
		if (kbAttr != null) {
			if (active && !kbAttr.hasModifier(JUGGERNAUT_KB_RESIST)) {
				kbAttr.applyModifier(JUGGERNAUT_KB_RESIST);
			} else if (!active && kbAttr.hasModifier(JUGGERNAUT_KB_RESIST)) {
				kbAttr.removeModifier(JUGGERNAUT_KB_RESIST);
			}
		}
	}

	@Override
	public boolean shouldExecute() {
		if (theMaid.isMaidWait()) return false;

		ItemStack mainHand = theMaid.getHeldItemMainhand();
		ItemStack offHand = theMaid.getHeldItemOffhand();
		boolean hasMainAxe = !mainHand.isEmpty() && mainHand.getItem() instanceof ItemAxe;
		boolean hasOffAxe = !offHand.isEmpty() && offHand.getItem() instanceof ItemAxe;
		
		if (!(hasMainAxe && hasOffAxe)) return false;

		EntityLivingBase lentity = theMaid.getAttackTarget();
		if (lentity == null && theMaid.getRevengeTarget() != null && theMaid.getRevengeTarget().isEntityAlive()) {
			theMaid.setAttackTarget(theMaid.getRevengeTarget());
			lentity = theMaid.getAttackTarget();
		}

		if (lentity == null || !lentity.isEntityAlive()) return false;
		
		entityTarget = lentity;
		pathToTarget = theMaid.getNavigator().getPathToXYZ(entityTarget.posX, entityTarget.posY, entityTarget.posZ);
		attackRange = (double)theMaid.width + (double)entityTarget.width + 0.8D;
		attackRange *= attackRange;

		if (theMaid.isFreedom() && !theMaid.isWithinHomeDistanceFromPosition(entityTarget.getPosition())) return false;
		if ((pathToTarget != null) || (theMaid.getDistanceSq(entityTarget.posX, entityTarget.getEntityBoundingBox().minY, entityTarget.posZ) <= attackRange)) return true;
		
		theMaid.setAttackTarget(null);
		theMaid.setRevengeTarget(null);
		return false;
	}

	@Override
	public void startExecuting() {
		if(entityTarget != null && !entityTarget.isDead){
			theMaid.playLittleMaidVoiceSound(EnumSound.FIND_TARGET_B, true);
		}
		if (pathToTarget != null) theMaid.getNavigator().setPath(pathToTarget, moveSpeed);
		else theMaid.getNavigator().clearPath();
		
		rerouteTimer = 0;
		theMaid.stopActiveHand(); 
		theMaid.maidAvatar.stopActiveHand();
		this.lastTickHealth = theMaid.getHealth(); 
		this.killClaimed = false;
	}

	@Override
	public boolean shouldContinueExecuting() {
		if (actionDelayTimer > 0 || pendingDash || isDashBuff || pendingOffhandStrike || (isFrenzy && slashCount > 0)) {
			if (entityTarget != null && entityTarget.isEntityAlive() && !entityTarget.isDead) return true; 
		}

		if (!theMaid.isFreedom() && theMaid.getMaidMasterEntity() instanceof EntityLivingBase) {
			EntityLivingBase master = (EntityLivingBase) theMaid.getMaidMasterEntity();
			boolean isMasterMoving = Math.abs(master.posX - master.prevPosX) > 0.02D || Math.abs(master.posZ - master.prevPosZ) > 0.02D;
			double maxDistSq = isMasterMoving ? 225.0D : 729.0D; 
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

		if (!MaidHelper.isTargetReachable(this.theMaid, lentity, 0.0D)) return false;
		if (!isReroute && theMaid.getNavigator().noPath()) return false;
		return true;
	}

	@Override
	public void resetTask() {
		entityTarget = null;
		theMaid.getNavigator().clearPath();
		theMaid.setAttackTarget(null);
		theMaid.setRevengeTarget(null);
		theMaid.stopActiveHand(); 
		theMaid.maidAvatar.stopActiveHand();

		actionDelayTimer = 0;
		pendingDash = false;
		isDashBuff = false;
		pendingOffhandStrike = false;
		setJuggernaut(false); // 彻底重置时剥离霸体
	}

	private void checkKillExtension() {
		if (this.entityTarget != null && (this.entityTarget.isDead || this.entityTarget.getHealth() <= 0.0F)) {
			if (this.isFrenzy && !this.killClaimed) {
				this.frenzyTimer += 60; 
				this.slashCount += 21;  
				this.killClaimed = true;
				System.err.println("[LMR-BERSERKER] 🩸 杀戮续航触发！时间+3秒，连斩补给+21次！");
			}
		}
	}

	@Override
	public void updateTask() {
		if (entityTarget == null || entityTarget.isDead || !entityTarget.isEntityAlive()) {
			checkKillExtension();
			resetTask();
			return;
		}

		if (this.lastTickHealth < 0) this.lastTickHealth = theMaid.getHealth();
		if (this.soundDelayTimer > 0) this.soundDelayTimer--;
		if (this.healthLockTimer > 0) this.healthLockTimer--;

		float currentHp = theMaid.getHealth();
		float maxHp = theMaid.getMaxHealth();

		if (this.isFrenzy && (currentHp / maxHp) > 0.6F) {
			this.isFrenzy = false;
			this.slashCount = 0;
			this.healthLockTimer = 0;
			theMaid.setBloodsuck(false);
			System.err.println("[LMR-BERSERKER] 🩸 血量恢复至 60% 以上，连砍狂暴状态解除。");
		}

		// =======================================================
		// 🌟 3 秒真伤锁血、爆炸免疫 与 易伤系统
		// =======================================================
		if (this.lastTickHealth > currentHp) {
			float damageTaken = this.lastTickHealth - currentHp;

			if (this.isFrenzy) {
				if (this.healthLockTimer > 0) {
					if (currentHp <= 0.0F) {
						boolean isExplosion = false;
						net.minecraft.util.DamageSource lastSrc = null;
						try {
							lastSrc = theMaid.getLastDamageSource();
						} catch (Throwable t) {
							try {
								lastSrc = net.minecraftforge.fml.common.ObfuscationReflectionHelper.getPrivateValue(EntityLivingBase.class, theMaid, "field_70718_bc");
							} catch (Exception e) {}
						}

						if (lastSrc != null && lastSrc.isExplosion()) {
							isExplosion = true;
						}

						if (isExplosion) {
							theMaid.setHealth(this.lastTickHealth);
							theMaid.isDead = false;
							theMaid.deathTime = 0;
							System.err.println("[LMR-BERSERKER] 💥 触发爆炸绝对锁血，免疫爆炸致死！");
						} else {
							this.healthLockTimer = 0;
							this.isFrenzy = false;
							System.err.println("[LMR-BERSERKER] 💀 受到非爆炸致命伤，锁血失效！");
						}
					} else {
						theMaid.setHealth(this.lastTickHealth);
					}
				} else {
					float effectiveDamage = damageTaken * 1.5F * (1.0F - this.comboDefBonus);
					float difference = effectiveDamage - damageTaken;
					theMaid.setHealth(currentHp - difference); 
				}
			} else if (this.hasBerserkPerm) {
				if (this.lastTickHealth > maxHp * 0.5F && currentHp <= maxHp * 0.5F && currentHp > 0.0F) {
					theMaid.setHealth(maxHp * 0.5F);
					this.hasBerserkPerm = false;
					this.isFrenzy = true;
					this.frenzyTimer = 140; 
					this.slashCount = 50;
					this.comboDamageBonus = 0.0F;
					this.comboDefBonus = 0.0F;
					this.frenzyTarget = this.entityTarget;
					this.isSingleTarget = true;
					this.killClaimed = false;
					
					this.healthLockTimer = 60; 
					
					theMaid.setBloodsuck(true); 
					System.err.println("[LMR-BERSERKER] 🩸 绝境爆发！获取 3 秒初始锁血，切入猩红连斩！");
				}
			}
		}

		if (!this.hasBerserkPerm && !this.isFrenzy && (currentHp / maxHp) >= 0.9F) {
			this.hasBerserkPerm = true;
			System.err.println("[LMR-BERSERKER] 💚 状态回满，重新获取下一次狂暴许可。");
		}

		if (this.isFrenzy) {
			if (theMaid.isRiding()) {
				theMaid.dismountRidingEntity();
			}
			if (theMaid.isBeingRidden()) {
				theMaid.removePassengers();
			}

			this.frenzyTimer--;
			if (this.frenzyTimer <= 0) {
				this.isFrenzy = false;
				theMaid.setBloodsuck(false);
			}
		}

		theMaid.getLookHelper().setLookPositionWithEntity(entityTarget, 30F, 30F);

		// =======================================================
		// 0. 副手无情追击 (突刺后处理)
		// =======================================================
		if (pendingOffhandStrike) {
			if (comboDelayTimer > 0) comboDelayTimer--;
			if (comboDelayTimer <= 0) {
				pendingOffhandStrike = false;
				theMaid.swingArm(net.minecraft.util.EnumHand.OFF_HAND);
				entityTarget.hurtResistantTime = 0;
				entityTarget.attackEntityFrom(net.minecraft.util.DamageSource.causeMobDamage(theMaid), this.storedOffhandDamage);
				
				ItemStack offItem = theMaid.getHeldItemOffhand();
				if (!offItem.isEmpty()) offItem.damageItem(1, theMaid);

				if (entityTarget instanceof EntityLivingBase && !this.isFrenzy) {
					((EntityLivingBase)entityTarget).knockBack(theMaid, 1.5F, 
						(double)MathHelper.sin(theMaid.rotationYaw * 0.017453292F), 
						(double)(-MathHelper.cos(theMaid.rotationYaw * 0.017453292F)));
					entityTarget.velocityChanged = true;
				}

				theMaid.playSound(net.minecraft.init.SoundEvents.ENTITY_PLAYER_ATTACK_CRIT, 1.0F, 0.8F);
				if (theMaid.getEntityWorld() instanceof net.minecraft.world.WorldServer) {
					((net.minecraft.world.WorldServer)theMaid.getEntityWorld()).spawnParticle(
						net.minecraft.util.EnumParticleTypes.CRIT, 
						entityTarget.posX, entityTarget.posY + entityTarget.height / 2.0F, entityTarget.posZ, 25, 0.4D, 0.4D, 0.4D, 0.2D
					);
				}

				if (entityTarget.getHealth() <= 0.0F || entityTarget.isDead) {
					this.nextDashTime = theMaid.getEntityWorld().getTotalWorldTime() + 200L;
				}

				this.actionDelayTimer = getWeaponCooldown();
				checkKillExtension();
				
				// 🌟 全局霸体统一判定，不再零散调用
				setJuggernaut(this.isDashBuff || this.isFrenzy);
				this.lastTickHealth = theMaid.getHealth();
				return;
			}
			
			setJuggernaut(this.isDashBuff || this.isFrenzy);
			this.lastTickHealth = theMaid.getHealth();
			return; 
		}

		// =======================================================
		// 1. 突刺命中之【主手第一刀】
		// =======================================================
		if (this.isDashBuff) {
			if (theMaid.onGround && Math.abs(theMaid.motionX) < 0.05D && Math.abs(theMaid.motionZ) < 0.05D) {
				this.isDashBuff = false;
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
					
					setJuggernaut(this.isDashBuff || this.isFrenzy);
					this.lastTickHealth = theMaid.getHealth();
					return; 
				} 
				else if (distance <= 1.5D || theMaid.getEntityBoundingBox().grow(0.8D, 0.8D, 0.8D).intersects(entityTarget.getEntityBoundingBox())) {
					
					float mainBaseDmg = (float)theMaid.getEntityAttribute(SharedMonsterAttributes.ATTACK_DAMAGE).getAttributeValue();
					float mainEnchantDmg = net.minecraft.enchantment.EnchantmentHelper.getModifierForCreature(theMaid.getHeldItemMainhand(), entityTarget.getCreatureAttribute());
					float mainTotal = mainBaseDmg + mainEnchantDmg;

					float offBaseDmg = 0.0F;
					ItemStack offItem = theMaid.getHeldItemOffhand();
					if (!offItem.isEmpty()) {
						com.google.common.collect.Multimap<String, net.minecraft.entity.ai.attributes.AttributeModifier> modifiers = offItem.getAttributeModifiers(net.minecraft.inventory.EntityEquipmentSlot.MAINHAND);
						for (net.minecraft.entity.ai.attributes.AttributeModifier mod : modifiers.get(SharedMonsterAttributes.ATTACK_DAMAGE.getName())) {
							offBaseDmg += (float)mod.getAmount();
						}
					}
					float offEnchantDmg = net.minecraft.enchantment.EnchantmentHelper.getModifierForCreature(offItem, entityTarget.getCreatureAttribute());
					float offTotal = offBaseDmg + offEnchantDmg;

					float finalDamage = (mainTotal + offTotal) * 1.5F;

					double prevMotionX = entityTarget.motionX;
					double prevMotionY = entityTarget.motionY;
					double prevMotionZ = entityTarget.motionZ;

					boolean isHit = theMaid.attackEntityAsMob(entityTarget);

					if (isHit) {
						entityTarget.motionX = prevMotionX;
						entityTarget.motionY = prevMotionY;
						entityTarget.motionZ = prevMotionZ;
						entityTarget.velocityChanged = true;

						this.storedOffhandDamage = finalDamage - mainTotal;

						if (entityTarget.getHealth() <= 0.0F || entityTarget.isDead) {
							this.nextDashTime = theMaid.getEntityWorld().getTotalWorldTime() + 200L;
						} else {
							this.pendingOffhandStrike = true;
							this.comboDelayTimer = 5;
						}
					} else {
						this.actionDelayTimer = getWeaponCooldown();
					}

					this.isDashBuff = false;
					theMaid.motionX = 0.0D;
					theMaid.motionY = 0.0D; 
					theMaid.motionZ = 0.0D;
					theMaid.velocityChanged = true;
					checkKillExtension();
					
					setJuggernaut(this.isDashBuff || this.isFrenzy);
					this.lastTickHealth = theMaid.getHealth();
					return; 
				} else {
					this.isDashBuff = false;
				}
			}
		}

		// =======================================================
		// 2. 突刺起步前摇
		// =======================================================
		if (actionDelayTimer > 0) {
			actionDelayTimer--;
			
			if (pendingDash) {
				double forceLookX = entityTarget.posX - theMaid.posX;
				double forceLookZ = entityTarget.posZ - theMaid.posZ;
				float strictTargetYaw = (float)(Math.atan2(forceLookZ, forceLookX) * 180.0D / Math.PI) - 90.0F;
				theMaid.rotationYaw = strictTargetYaw;
				theMaid.rotationYawHead = strictTargetYaw;
				theMaid.renderYawOffset = strictTargetYaw;
				
				if (actionDelayTimer <= 0) {
					pendingDash = false;
					theMaid.playSound(net.minecraft.init.SoundEvents.ENTITY_PLAYER_ATTACK_SWEEP, 1.0F, 1.0F);
					theMaid.playLittleMaidVoiceSound(EnumSound.FIND_TARGET_B, true);
					theMaid.stopActiveHand(); 
					theMaid.maidAvatar.stopActiveHand(); 
					
					theMaid.motionX = 0.0D;
					theMaid.motionY = 0.0D;
					theMaid.motionZ = 0.0D;
					theMaid.velocityChanged = true;

					double dX = entityTarget.posX - theMaid.posX;
					double dZ = entityTarget.posZ - theMaid.posZ;
					double distance = Math.sqrt(dX * dX + dZ * dZ);
					
					if (distance >= 0.0001D) {
						theMaid.motionX = (dX / distance) * 1.2D; 
						theMaid.motionZ = (dZ / distance) * 1.2D;
						theMaid.motionY = 0.2D;  
						theMaid.velocityChanged = true;
					}
					
					theMaid.hurtResistantTime = 0; 
					this.isDashBuff = true; 
				}
				
				setJuggernaut(this.isDashBuff || this.isFrenzy);
				this.lastTickHealth = theMaid.getHealth();
				return; 
			}
		}

		// =======================================================
		// 3. 寻路追击
		// =======================================================
		if (--rerouteTimer <= 0) {
			if (isReroute || theMaid.getEntitySenses().canSee(entityTarget)) {
				rerouteTimer = 4 + theMaid.getRNG().nextInt(7);
				double distToTarget = theMaid.getDistanceSq(entityTarget);
				
				float burstSpeed = moveSpeed * 1.5F; 
				if (distToTarget < 36.0D || this.isFrenzy) burstSpeed = moveSpeed * 1.8F; 
				theMaid.getNavigator().tryMoveToXYZ(entityTarget.posX, entityTarget.posY, entityTarget.posZ, burstSpeed);
			} else {
				theMaid.setAttackTarget(null);
				theMaid.setRevengeTarget(null);
			}
		}

		// =======================================================
		// 4. 斩击判定
		// =======================================================
		if (this.actionDelayTimer <= 0 && !this.isDashBuff && !this.pendingOffhandStrike) {
			double currentDistSq = theMaid.getDistanceSq(entityTarget.posX, entityTarget.getEntityBoundingBox().minY, entityTarget.posZ);
			
			if (currentDistSq >= 25.0D && currentDistSq <= 49.0D) {
				if (theMaid.getEntityWorld().getTotalWorldTime() >= this.nextDashTime) {
					this.pendingDash = true;
					this.actionDelayTimer = 10; 
					
					setJuggernaut(this.isDashBuff || this.isFrenzy);
					this.lastTickHealth = theMaid.getHealth();
					return;
				}
			}

			double attackRangeSq = (double)theMaid.width + (double)entityTarget.width + 0.8D;
			attackRangeSq *= attackRangeSq;
			
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
					
					// 🌟 终极猩红连斩
					if (this.isFrenzy && this.slashCount > 0) {
						if (this.isSingleTarget && entityTarget != this.frenzyTarget) {
							this.isSingleTarget = false;
						}

						if (this.isSingleTarget && entityTarget.getHealth() <= entityTarget.getMaxHealth() * 0.01F) {
							entityTarget.setHealth(0.0F);
							System.err.println("[LMR-BERSERKER] 🩸 目标触发 1% 极刑底线，被绞肉机无情碎肉！");
						}

						float mainBaseDmg = (float)theMaid.getEntityAttribute(SharedMonsterAttributes.ATTACK_DAMAGE).getAttributeValue();
						float mainEnch = net.minecraft.enchantment.EnchantmentHelper.getModifierForCreature(theMaid.getHeldItemMainhand(), entityTarget.getCreatureAttribute());
						float rawDmg = mainBaseDmg + mainEnch;
						
						float finalHitDmg = rawDmg * 0.4F * (1.0F + this.comboDamageBonus);
						
						boolean isFinalStrike = (this.slashCount == 1);
						if (isFinalStrike && this.isSingleTarget) {
							float offBaseDmg = 0.0F;
							ItemStack offItem = theMaid.getHeldItemOffhand();
							if (!offItem.isEmpty()) {
								com.google.common.collect.Multimap<String, net.minecraft.entity.ai.attributes.AttributeModifier> mods = offItem.getAttributeModifiers(net.minecraft.inventory.EntityEquipmentSlot.MAINHAND);
								for (net.minecraft.entity.ai.attributes.AttributeModifier mod : mods.get(SharedMonsterAttributes.ATTACK_DAMAGE.getName())) offBaseDmg += (float)mod.getAmount();
							}
							float offEnch = net.minecraft.enchantment.EnchantmentHelper.getModifierForCreature(offItem, entityTarget.getCreatureAttribute());
							
							finalHitDmg = (rawDmg + offBaseDmg + offEnch) * 1.5F * (1.0F + this.comboDamageBonus);
							theMaid.playSound(net.minecraft.init.SoundEvents.BLOCK_ANVIL_FALL, 1.0F, 0.5F); 
							System.err.println("[LMR-BERSERKER] 💥 终焉双斧合璧！爆发终极绝杀伤害！");
						}

						entityTarget.hurtResistantTime = 0; 
						boolean isHit = entityTarget.attackEntityFrom(net.minecraft.util.DamageSource.causeMobDamage(theMaid), finalHitDmg);

						if (isHit) {
							this.comboDamageBonus = Math.min(1.0F, this.comboDamageBonus + 0.01F);
							this.comboDefBonus = Math.min(0.60F, this.comboDefBonus + 0.01F);

							if (this.slashCount % 3 == 0) {
								theMaid.swingArm(this.slashCount % 2 == 0 ? net.minecraft.util.EnumHand.MAIN_HAND : net.minecraft.util.EnumHand.OFF_HAND);
							}
							
							if (this.soundDelayTimer <= 0) {
								theMaid.playSound(net.minecraft.init.SoundEvents.ENTITY_PLAYER_ATTACK_SWEEP, 1.0F, 1.0F);
								this.soundDelayTimer = 20 + theMaid.getRNG().nextInt(20);
							}
						}
						
						this.slashCount--;
						this.actionDelayTimer = 2; 
						checkKillExtension();
					} 
					else {
						boolean isHit = theMaid.attackEntityAsMob(entityTarget); 
						if (isHit) {
							if (!theMaid.onGround) {
								theMaid.playSound(net.minecraft.init.SoundEvents.ENTITY_PLAYER_ATTACK_CRIT, 1.0F, 1.0F);
								if (theMaid.getEntityWorld() instanceof net.minecraft.world.WorldServer) {
									((net.minecraft.world.WorldServer)theMaid.getEntityWorld()).spawnParticle(
										net.minecraft.util.EnumParticleTypes.CRIT, 
										entityTarget.posX, entityTarget.posY + (entityTarget.height / 2.0F), entityTarget.posZ, 15, 0.3D, 0.3D, 0.3D, 0.2D
									);
								}
								float baseAttackDamage = (float)theMaid.getEntityAttribute(net.minecraft.entity.SharedMonsterAttributes.ATTACK_DAMAGE).getAttributeValue();
								entityTarget.attackEntityFrom(net.minecraft.util.DamageSource.causeMobDamage(theMaid), baseAttackDamage * 0.5F);
							} 
						}
						this.actionDelayTimer = getWeaponCooldown();
						checkKillExtension();
					}
				} 
			} 
		}

		// 🌟 最终状态统一监听器：维持或解除霸体
		setJuggernaut(this.isDashBuff || this.isFrenzy);
		this.lastTickHealth = theMaid.getHealth();
	} 

	@Override
	public void setEnable(boolean pFlag) { fEnable = pFlag; }
	@Override
	public boolean getEnable() { return fEnable; }
}
