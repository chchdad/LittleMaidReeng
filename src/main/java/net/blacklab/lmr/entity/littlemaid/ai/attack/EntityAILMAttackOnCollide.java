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
 * メイドさんの直接攻撃系処理 (修复 inGround 编译报错 + 动量静止判定 + 完美原味连招)
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
	
	// 连招状态机变量
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

	// 🩸 终极动态血线状态机
	protected boolean isDynamicBerserk = false; 
	protected boolean hasBerserkPerm = true;    
	protected boolean hitTenPercent = false;    

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

	@Override
	public boolean shouldExecute() {
		EntityLivingBase lentity = theMaid.getAttackTarget();
		
		// 强制仇恨反击锁。如果被打了但没目标，强行锁定打她的人！
		if (lentity == null && theMaid.getRevengeTarget() != null && theMaid.getRevengeTarget().isEntityAlive()) {
			theMaid.setAttackTarget(theMaid.getRevengeTarget());
			lentity = theMaid.getAttackTarget();
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
		theMaid.stopActiveHand(); 
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

		if (!MaidHelper.isTargetReachable(this.theMaid, lentity, 0.0D)) {
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
		theMaid.stopActiveHand(); 
		theMaid.maidAvatar.stopActiveHand();
		
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
		
		if (this.lastTickHealth < 0) this.lastTickHealth = theMaid.getHealth();

		theMaid.getLookHelper().setLookPositionWithEntity(entityTarget, 30F, 30F);
		
		// =========================================================
		// 🩸 动态血线阶梯状态机
		// =========================================================
		float hpPct = theMaid.getHealth() / theMaid.getMaxHealth();

		if (hpPct <= 0.10F && !this.hitTenPercent) {
			this.hitTenPercent = true;
			System.err.println("[LMR-STATE-DEBUG] 🩸 跌入濒死血线(<=10%)! 激活强效恢复许可!");
		}

		if (this.isDynamicBerserk) {
			if (hpPct <= 0.25F) {
				this.isDynamicBerserk = false;
				System.err.println("[LMR-STATE-DEBUG] 🩸 血量过低(<=25%)! 强制退出狂暴, 转为防守!");
			} else if (hpPct >= 0.60F) {
				this.isDynamicBerserk = false;
				System.err.println("[LMR-STATE-DEBUG] 🩸 血量回复(>=60%)! 解除狂暴状态!");
			}
		}

		if (!this.hasBerserkPerm && !this.isDynamicBerserk) {
			if (this.hitTenPercent && hpPct >= 0.80F) {
				this.hasBerserkPerm = true;
				this.hitTenPercent = false; 
				System.err.println("[LMR-STATE-DEBUG] 🩸 劫后余生回血(>=80%)! 提前获取下一次狂暴许可!");
			} else if (!this.hitTenPercent && hpPct >= 0.90F) {
				this.hasBerserkPerm = true;
				if (logSpamLimiter % 100 == 0) System.err.println("[LMR-STATE-DEBUG] 🩸 状态健康(>=90%)! 正常获取狂暴许可!");
			}
		}

		if (this.hasBerserkPerm && !this.isDynamicBerserk) {
			if (hpPct <= 0.50F && hpPct > 0.25F) {
				this.isDynamicBerserk = true;
				this.hasBerserkPerm = false; 
				System.err.println("[LMR-STATE-DEBUG] 🩸 血线跌破50%! 触发高频连招求生狂暴!");
			}
		}

		// =========================================================
		// 🏹 防箭矢侧滑闪避
		// =========================================================
		if (this.dodgeCooldown > 0) this.dodgeCooldown--;

		if (this.dodgeCooldown <= 0) {
			java.util.List<Entity> projectiles = worldObj.getEntitiesWithinAABBExcludingEntity(theMaid, theMaid.getEntityBoundingBox().grow(6.0D, 2.0D, 6.0D));
			for (Entity proj : projectiles) {
				if (proj instanceof net.minecraft.entity.projectile.EntityArrow || 
					proj instanceof net.minecraft.entity.projectile.EntityThrowable || 
					proj instanceof net.minecraft.entity.projectile.EntityFireball) {
					
					// 🌟 修复：不再调用 protected 的 inGround，直接通过三轴物理动量判定！插在地上或墙上的箭必然静止！
					if (Math.abs(proj.motionX) < 0.01D && Math.abs(proj.motionY) < 0.01D && Math.abs(proj.motionZ) < 0.01D) {
						continue; // 无视没有动能的死弹射物
					}
					
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
							System.err.println("[LMR-STATE-DEBUG] 💨 侦测到危险飞行物，战术侧滑闪避!");
							break; 
						}
					}
				}
			}
		}

		// =======================================================
		// 1. 红温超视距救主狂暴
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

		boolean isOwnerBerserk = this.isDynamicBerserk || (this.rescueBerserkTimer > 0);
		theMaid.setBloodsuck(isOwnerBerserk); 
		
		// =======================================================
		// 2. Dash 追击 (原版腾空暴击与动量归零)
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
					this.lastTickHealth = theMaid.getHealth();
					return; 
				} 
				else if (distance <= 1.5D || theMaid.getEntityBoundingBox().grow(0.8D, 0.8D, 0.8D).intersects(entityTarget.getEntityBoundingBox())) {
					theMaid.attackEntityAsMob(entityTarget);
					
					if (rescueBerserkTimer > 100) {
						rescueBerserkTimer = 100;
						this.setMaidOverDrive(100); 
					}

					this.isDashBuff = false;
					this.actionDelayTimer = getWeaponCooldown();
					
					// 动量清零，制造停顿卡肉感
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

					System.err.println("[LMR-STATE-DEBUG] 💥 腾空突刺命中！动量清零，打出暴击击退！");
					theMaid.playSound(net.minecraft.init.SoundEvents.ENTITY_PLAYER_ATTACK_CRIT, 1.0F, 1.0F);
					if (worldObj instanceof net.minecraft.world.WorldServer) {
						((net.minecraft.world.WorldServer)worldObj).spawnParticle(
							net.minecraft.util.EnumParticleTypes.CRIT, 
							entityTarget.posX, entityTarget.posY + entityTarget.height / 2.0F, entityTarget.posZ, 
							15, 0.3D, 0.3D, 0.3D, 0.2D
						);
					}
					this.lastTickHealth = theMaid.getHealth();
					return; 
				} else {
					this.isDashBuff = false;
				}
			}
		}

		// =======================================================
		// 3. FSM 连招 (强制锁头 + 完美格挡锁血 + 物理后撤 -> 拔刀突刺)
		// =======================================================
		if (actionDelayTimer > 0) {
			actionDelayTimer--;
			
			if (pendingBackstep || pendingDash) {
				// 强行掰回头与身体：绝对不露后背！
				double forceLookX = entityTarget.posX - theMaid.posX;
				double forceLookZ = entityTarget.posZ - theMaid.posZ;
				float strictTargetYaw = (float)(Math.atan2(forceLookZ, forceLookX) * 180.0D / Math.PI) - 90.0F;
				theMaid.rotationYaw = strictTargetYaw;
				theMaid.rotationYawHead = strictTargetYaw;
				theMaid.renderYawOffset = strictTargetYaw;
				
				if (pendingBackstep) {
					theMaid.motionX = 0.0D;
					theMaid.motionZ = 0.0D;
					theMaid.motionY = -0.08D; 
					
					this.isGuard = true; 
					// 真身与虚拟玩家同步举盾，确保视觉生效
					if (!theMaid.isHandActive()) {
						theMaid.setActiveHand(net.minecraft.util.EnumHand.MAIN_HAND);
					}
					if (!theMaid.maidAvatar.isHandActive()) {
						theMaid.maidAvatar.setActiveHand(net.minecraft.util.EnumHand.MAIN_HAND);
					}
					
					theMaid.addPotionEffect(new net.minecraft.potion.PotionEffect(net.minecraft.init.MobEffects.RESISTANCE, 5, 1, false, false));
					
					if (worldObj instanceof net.minecraft.world.WorldServer && logSpamLimiter % 2 == 0) {
						((net.minecraft.world.WorldServer)worldObj).spawnParticle(
							net.minecraft.util.EnumParticleTypes.CRIT, 
							theMaid.posX + (theMaid.getRNG().nextFloat()-0.5), 
							theMaid.posY + 0.5D, 
							theMaid.posZ + (theMaid.getRNG().nextFloat()-0.5), 
							2, 0.0D, 0.0D, 0.0D, 0.1D
						);
					}
				}
				
				if (pendingDash) {
					this.isGuard = true;
					if (!theMaid.isHandActive()) {
						theMaid.setActiveHand(net.minecraft.util.EnumHand.MAIN_HAND);
					}
					if (!theMaid.maidAvatar.isHandActive()) {
						theMaid.maidAvatar.setActiveHand(net.minecraft.util.EnumHand.MAIN_HAND);
					}
				}

				// 🛡️ 物理硬核防御逻辑
				if (this.isGuard) {
					if (theMaid.hurtTime == 10 && this.lastTickHealth > theMaid.getHealth()) {
						float damageTaken = this.lastTickHealth - theMaid.getHealth();
						theMaid.heal(damageTaken); 
						theMaid.playSound(net.minecraft.init.SoundEvents.ITEM_SHIELD_BLOCK, 1.0F, 0.8F + theMaid.getRNG().nextFloat() * 0.4F);
						System.err.println("[LMR-STATE-DEBUG] 🛡️ 触发完美格挡！抵消伤害: " + damageTaken);
					}
					
					if (theMaid.getHealth() <= 1.0F) {
						theMaid.setHealth(1.0F);
						if (theMaid.isDead) {
							theMaid.isDead = false;
							theMaid.deathTime = 0;
							System.err.println("[LMR-STATE-DEBUG] 🛡️ 致命伤格挡！强行锁住1滴血不死！");
						}
					}
				}
				
				if (actionDelayTimer <= 0) {
					if (pendingBackstep) {
						pendingBackstep = false; 
						System.err.println("[LMR-STATE-DEBUG] 💨 招架结束，后撤拉开距离！");
						
						boolean hasArmor = false;
						for (net.minecraft.item.ItemStack armorStack : theMaid.getArmorInventoryList()) {
							if (armorStack != null && !armorStack.isEmpty()) { hasArmor = true; break; }
						}
						if (hasArmor) {
							theMaid.playSound(net.minecraft.init.SoundEvents.ITEM_ARMOR_EQUIP_LEATHER, 0.8F, 1.2F);
						} else {
							theMaid.playSound(net.minecraft.init.SoundEvents.ITEM_ARMOR_EQUIP_GENERIC, 0.8F, 1.2F);
						}
						
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
						System.err.println("[LMR-STATE-DEBUG] 🚀 燕返出膛！绝境突刺发动 (附带腾空)！");
						
						theMaid.playSound(net.minecraft.init.SoundEvents.ENTITY_PLAYER_ATTACK_SWEEP, 1.0F, 1.0F);
						theMaid.playLittleMaidVoiceSound(theMaid.isBloodsuck() ? EnumSound.FIND_TARGET_B : EnumSound.FIND_TARGET_N, true);
						
						this.isGuard = false;
						theMaid.stopActiveHand(); 
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
				this.lastTickHealth = theMaid.getHealth();
				return; 
			}
		}

		// =======================================================
		// 4. 寻路 (狂暴的高额移速)
		// =======================================================
		if (--rerouteTimer <= 0) {
			if (isReroute || theMaid.getEntitySenses().canSee(entityTarget)) {
				rerouteTimer = 4 + theMaid.getRNG().nextInt(7);
				
				double distToTarget = theMaid.getDistanceSq(entityTarget);
				float burstSpeed = moveSpeed;
				
				if (isOwnerBerserk) {
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
		// 5. 斩击判定 (瞬转 + 攻速 + 剑刃横扫)
		// =======================================================
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
					
					this.actionDelayTimer = getWeaponCooldown();

					if (!isOwnerBerserk) {
						float triggerChance = this.isDynamicBerserk ? 0.50F : 0.25F;
						if (theMaid.getRNG().nextFloat() < triggerChance) {
							this.actionDelayTimer = this.isDynamicBerserk ? 20 : 25; 
							this.pendingBackstep = true; 
							System.err.println("[LMR-STATE-DEBUG] ⚠️ 平A命中！进入 20-25 刻长前摇举盾防守...");
						} 
					} 
				} 
			} 
		}

		this.lastTickHealth = theMaid.getHealth();
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
