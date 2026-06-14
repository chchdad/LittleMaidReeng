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
		}

		double lfollowRange = 0.0D;
		if (!(
				taskOwner instanceof EntityLittleMaid && ((EntityLittleMaid) taskOwner).jobController.getActiveModeClass() != null &&
				(lfollowRange = ((EntityLittleMaid) taskOwner).jobController.getActiveModeClass().getDistanceToSearchTargets()) > 0
				)) {
			lfollowRange = getTargetDistance();
		}

		// ========================================================
		// 核心修改 3：双重雷达并网技术 (彻底消除视野盲区)
		// ========================================================
		if (lfollowRange < 16.0D) {
			lfollowRange = 16.0D;
		}

		net.minecraft.util.math.AxisAlignedBB searchBox;
		if (theMaid.getMaidMasterEntity() != null && !theMaid.isBloodsuck()) {
			net.minecraft.util.math.AxisAlignedBB masterBox = theMaid.getMaidMasterEntity().getEntityBoundingBox().grow(lfollowRange, 8.0D, lfollowRange);
			net.minecraft.util.math.AxisAlignedBB maidBox = taskOwner.getEntityBoundingBox().grow(lfollowRange, 8.0D, lfollowRange);
			searchBox = masterBox.union(maidBox);
			
			theNearestAttackableTargetSorter.setEntity(theMaid.getMaidMasterEntity());
		} else {
			searchBox = taskOwner.getEntityBoundingBox().grow(lfollowRange, 8.0D, lfollowRange);
			theNearestAttackableTargetSorter.setEntity(theMaid);
		}

		List<T> llist = this.taskOwner.getEntityWorld().getEntitiesWithinAABB(targetClass, searchBox);
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

	protected boolean isSuitableTargetLM(Entity pTarget, boolean par2) {
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

	protected boolean func_75295_a(Entity par1EntityLiving) {
		this.fretryCounter = 10 + this.taskOwner.getRNG().nextInt(5);
		Path var2 = taskOwner.getNavigator().getPathToXYZ(par1EntityLiving.posX, par1EntityLiving.posY, par1EntityLiving.posZ);

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
