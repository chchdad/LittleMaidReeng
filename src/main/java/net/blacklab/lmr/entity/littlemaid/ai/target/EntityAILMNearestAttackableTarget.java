package net.blacklab.lmr.entity.littlemaid.ai.target;

import java.util.Collections;
import java.util.Iterator;
import java.util.List;

import net.blacklab.lmr.entity.littlemaid.EntityLittleMaid;
import net.blacklab.lmr.entity.littlemaid.mode.EntityModeBase;
import net.blacklab.lmr.util.helper.MaidHelper;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.ai.EntityAINearestAttackableTarget;
import net.minecraft.pathfinding.Path;
import net.minecraft.pathfinding.PathPoint;
import net.minecraft.util.math.MathHelper;

public class EntityAILMNearestAttackableTarget<T extends EntityLivingBase> extends EntityAINearestAttackableTarget<T> {

	protected EntityLittleMaid theMaid;
	protected int targetChance;
	protected ComparatorLMNearestAttackableTargetSorter<?> theNearestAttackableTargetSorter;

	private boolean fretarget;
	private int fcanAttack;
	private int fretryCounter;

	public EntityAILMNearestAttackableTarget(EntityLittleMaid par1EntityLiving, Class<T> par2Class, int par4, boolean par5) {
		this(par1EntityLiving, par2Class, par4, par5, false);
	}

	public EntityAILMNearestAttackableTarget(EntityLittleMaid par1, Class<T> par2, int par4, boolean par5, boolean par6) {
		super(par1, par2, par4, par5, par6, null);
//		targetClass = par2;
		targetChance = par4;
		theNearestAttackableTargetSorter = new ComparatorLMNearestAttackableTargetSorter<T>(par1);
		fretarget = par6;
		theMaid = par1;

		setMutexBits(1);
	}


	@Override
	public boolean shouldExecute() {
		if (targetEntity != null && targetEntity.isEntityAlive() && taskOwner.getAttackTarget() == targetEntity) {
			return true;
		}

		if (this.targetChance > 0 && this.taskOwner.getRNG().nextInt(this.targetChance) != 0) {
			return false;
//		} else if (theMaid.getAttackTarget() != null) {
//			return true;
		}

		 		// =======================================================
		// 5. 极速斩击判定 (瞬间转身 + 110度防背刺视觉保护)
		// =======================================================
		double attackRangeSq = (double)theMaid.width + (double)entityTarget.width + 0.8D;
		attackRangeSq *= attackRangeSq;
		double currentDistSq = theMaid.getDistanceSq(entityTarget.posX, entityTarget.getEntityBoundingBox().minY, entityTarget.posZ);
		
		if (currentDistSq <= attackRangeSq) {
			// 【核心修正】：贴脸瞬间，强行把身体和头扭向怪物，根除转身慢导致的发呆！
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
			
			// 必须满足在正面 110 度内，且武器 CD 就绪
			boolean canSlashNow = (ld >= -0.35D) && (theMaid.getSwingStatusDominant().canAttack() || entityTarget.hurtResistantTime <= 5);

			if (canSlashNow) {
				System.out.println("[LMR-ATTACK-DEBUG] 转身锁定！贴脸瞬间出刀!");
				theMaid.attackEntityAsMob(entityTarget);
				
				float triggerChance = isBerserk ? 0.50F : 0.25F;
				if (theMaid.onGround && theMaid.getRNG().nextFloat() < triggerChance) {
					System.out.println("[LMR-ATTACK-DEBUG] 触发连招体系!");
					this.actionDelayTimer = 8; 
					this.pendingBackstep = true; 
					theMaid.hurtResistantTime = 40; 
				}
			}
		}
	} // 这是 updateTask() 方法的右大括号，请确保替换到这里

		
		if (theMaid.getMaidMasterEntity() != null && !theMaid.isBloodsuck()) {
			// ソーターを主中心へ
			theNearestAttackableTargetSorter.setEntity(theMaid.getMaidMasterEntity());
		} else {
			// 自分中心にソート
			theNearestAttackableTargetSorter.setEntity(theMaid);
		}
		Collections.sort(llist, theNearestAttackableTargetSorter);
		                Iterator<T> nearEntityCollectionsIterator = llist.iterator();
                while (nearEntityCollectionsIterator.hasNext()) {
                        T lentity = (T)nearEntityCollectionsIterator.next();
                        if (lentity == theMaid.getAttackTarget()) {
                                return true;
                        }
                        
                        System.out.println("[LMR-RADAR-DEBUG] 正在鉴定潜在目标: " + lentity.getName() + " | 距主人: " + lentity.getDistanceSq(theMaid.getMaidMasterEntity()) + " | 距女仆: " + lentity.getDistanceSq(theMaid));
                        
                        if (lentity.isEntityAlive() && this.isSuitableTargetLM(lentity, false)) {
                                System.out.println("[LMR-RADAR-DEBUG] -> 鉴定通过！雷达最终锁定: " + lentity.getName());
                                this.targetEntity = lentity;
                                return true;
                        } else {
                                System.out.println("[LMR-RADAR-DEBUG] -> 鉴定失败！直接丢弃: " + lentity.getName());
                        }
                }

		return false;
	}

	@Override
	public void startExecuting() {
		super.startExecuting();
		theMaid.setAttackTarget((EntityLivingBase)targetEntity);

		fcanAttack = 0;
		fretryCounter = 0;
	}

	//	@Override
	protected boolean isSuitableTargetLM(Entity pTarget, boolean par2) {
		// LMM用にカスタム
		// 非生物も対象のため別クラス
		if (pTarget == null) {
			return false;
		}

		if (pTarget == taskOwner) {
			return false;
		}
		if (pTarget == theMaid.getMaidMasterEntity()) {
			return false;
		}

		if (!pTarget.isEntityAlive()) {
			return false;
		}

		EntityModeBase lailm = theMaid.jobController.getActiveModeClass();
		if (lailm != null && lailm.isSearchEntity()) {
			if (!lailm.checkEntity(theMaid.getMaidModeString(), pTarget)) {
				return false;
			}
		                } else {
                        if (theMaid.getIFF(pTarget)) {
                                return false;
                        }
                        // Can't reach target
                        if (!MaidHelper.isTargetReachable(theMaid, pTarget, 0)) {
                                System.out.println("[LMR-RADAR-DEBUG] 拒绝原因: MaidHelper.isTargetReachable() 判定此怪无法抵达 (可能被墙挡住或距离过远)！");
                                return false;
                        }
                }

                // ターゲットが見えない
                if (shouldCheckSight && !taskOwner.getEntitySenses().canSee(pTarget)) {
                        System.out.println("[LMR-RADAR-DEBUG] 拒绝原因: 视线被遮挡 (canSee = false)！");
                        return false;
                }


		// 攻撃中止判定？
		if (this.fretarget) {
			if (--this.fretryCounter <= 0) {
				this.fcanAttack = 0;
			}

			if (this.fcanAttack == 0) {
				this.fcanAttack = this.func_75295_a(pTarget) ? 1 : 2;
			}

			if (this.fcanAttack == 2) {
				return false;
			}
		}

		return true;
	}

	// 最終位置が攻撃の間合いでなければ失敗
	protected boolean func_75295_a(Entity par1EntityLiving) {
		this.fretryCounter = 10 + this.taskOwner.getRNG().nextInt(5);
		Path var2 = taskOwner.getNavigator().getPathToXYZ(par1EntityLiving.posX, par1EntityLiving.posY, par1EntityLiving.posZ);
//		PathEntity var2 = this.taskOwner.getNavigator().getPathToEntityLiving(par1EntityLiving);

		if (var2 == null) {
			return false;
		}
		PathPoint var3 = var2.getFinalPathPoint();

		if (var3 == null) {
			return false;
		}
		int var4 = var3.x - MathHelper.floor(par1EntityLiving.posX);
		int var5 = var3.z - MathHelper.floor(par1EntityLiving.posZ);
		return var4 * var4 + var5 * var5 <= 2.25D;
	}

	@Override
	protected double getTargetDistance() {
		double targetd = 0;
		if (theMaid.jobController.getActiveModeClass() != null && (targetd = theMaid.jobController.getActiveModeClass().getDistanceToSearchTargets()) > 0) {
			return targetd;
		}
		return super.getTargetDistance();
	}

}
