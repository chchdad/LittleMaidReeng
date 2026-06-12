package net.blacklab.lmr.entity.littlemaid.ai;

import java.util.List;

import firis.lmlib.api.constant.EnumSound;
import net.blacklab.lmr.entity.littlemaid.EntityLittleMaid;
import net.blacklab.lmr.util.helper.ItemHelper;
import net.blacklab.lmr.util.helper.VectorUtil;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ai.EntityAIBase;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.pathfinding.Path;
import net.minecraft.pathfinding.PathNavigate;
import net.minecraft.util.math.MathHelper;

/**
 * アイテムを拾う (8格弹性跟随 + 32格视距兜底 + 3秒长臂猿防卡死)
 * @author firis-games
 */
public class EntityAILMCollectItem extends EntityAIBase {

	protected EntityLittleMaid theMaid;
	protected float moveSpeed;
	protected EntityItem targetItem;
	protected boolean lastAvoidWater;

	// ====== 物理防卡死长臂猿系统变量 ======
	private double lastPosX, lastPosZ;
	private int checkStuckTimer = 0;
	private int realStuckCount = 0;

	public EntityAILMCollectItem(EntityLittleMaid pEntityLittleMaid, float pmoveSpeed) {
		theMaid = pEntityLittleMaid;
		moveSpeed = pmoveSpeed;
		setMutexBits(3);
	}

	/**
	 * 【新增方法】：精准检测玩家是否正在发生实质性位移
	 */
	private boolean isPlayerMoving(EntityPlayer player) {
		if (player == null) return false;
		double dX = player.posX - player.prevPosX;
		double dZ = player.posZ - player.prevPosZ;
		// 水平移动距离的平方大于 0.001 视为正在移动
		return (dX * dX + dZ * dZ) > 0.001D;
	}

	@Override
	public boolean shouldExecute() {
		if(theMaid.isMaidWaitEx()) return false;
		
		if (theMaid.maidInventory.getFirstEmptyStack() > -1) {
			
			EntityPlayer ep = theMaid.getMaidMasterEntity() != null ? theMaid.getMaidMasterEntity() : theMaid.getEntityWorld().getClosestPlayerToEntity(theMaid, 16F);
			
			if (ep != null) {
				double distSq = theMaid.getDistanceSq(ep);
				
				// 【绝对死线兜底】：距离超过 32 格 (最低渲染距离 2 Chunk，1024.0D)，绝对不捡
				if (distSq > 1024.0D) {
					return false;
				}
				
				// 【弹性跟随判定】：距离超过 8 格 (64.0D)，且主人正在移动，优先跟车不捡破烂
				if (distSq > 64.0D && isPlayerMoving(ep)) {
					return false;
				}
			}

			// 扩大扫描范围，配合主人的挂机允许范围，提高干活效率
			List<EntityItem> llist = theMaid.getEntityWorld()
					.getEntitiesWithinAABB(EntityItem.class, 
							theMaid.getEntityBoundingBox().grow(12F, 4D, 12F));
							
			if (!llist.isEmpty()) {
				int li = theMaid.getRNG().nextInt(llist.size());
				EntityItem ei = llist.get(li);

				NBTTagCompound p = new NBTTagCompound();
				ei.writeEntityToNBT(p);
				if (!ei.isDead && ei.onGround && p.getShort("PickupDelay") <= 0 && !ei.isBurning()
						&& canEntityItemBeSeen(ei) && (ep == null ||
						ep.getDistanceSq(
								ei.posX + MathHelper.sin(ep.rotationYaw * 0.01745329252F) * 2.0D,
								ei.posY,
								ei.posZ - MathHelper.cos(ep.rotationYaw * 0.01745329252F) * 2.0D) > 7.5D))
				{
					ItemStack lstack = ei.getItem();
					if (!ItemHelper.isSugar(lstack)) {
						if (!theMaid.jobController.isActiveModeClass()) {
							return false;
						}
						if ((!theMaid.jobController.getActiveModeClass().checkItemStack(lstack))) {
							return false;
						}
					}
					theMaid.playLittleMaidVoiceSound(EnumSound.FIND_TARGET_I, true);
					targetItem = ei;
					return true;
				}
			}
		}

		return false;
	}

	@Override
	public void startExecuting() {
		super.startExecuting();
		this.lastPosX = theMaid.posX;
		this.lastPosZ = theMaid.posZ;
		this.checkStuckTimer = 0;
		this.realStuckCount = 0;
	}

	@Override
	public boolean shouldContinueExecuting() {
		EntityPlayer ep = theMaid.getMaidMasterEntity();
		
		if (ep != null) {
			double distSq = theMaid.getDistanceSq(ep);
			
			// 【执行中兜底】：跑去捡的路上，主人走远了超过 32 格，立刻放弃
			if (distSq > 1000.0D) {
				return false;
			}
			
			// 【执行中打断】：如果正在捡，但主人移动且距离拉开到 8 格以外，赶紧中止去追主人
			if (distSq > 64.0D && isPlayerMoving(ep)) {
				return false;
			}
		}
		
		return !targetItem.isDead && (theMaid.maidInventory.getFirstEmptyStack() > -1) && theMaid.getDistanceSq(targetItem) < 256D; // 允许目标在 16 格内追踪
	}

	@Override
	public void resetTask() {
		targetItem = null;
		theMaid.getNavigator().clearPath();
	}

	@Override
	public void updateTask() {
		theMaid.getLookHelper().setLookPositionWithEntity(targetItem, 30F, theMaid.getVerticalFaceSpeed());

		PathNavigate lnavigater = theMaid.getNavigator();
		if (lnavigater.noPath()) {
			Path lpath = lnavigater.getPathToXYZ(targetItem.posX, targetItem.posY, targetItem.posZ);
			lnavigater.setPath(lpath, moveSpeed);
		}

		// ====== 硬核物理防卡死检测 ======
		this.checkStuckTimer++;
		if (this.checkStuckTimer >= 20) { 
			
			double dX = theMaid.posX - this.lastPosX;
			double dZ = theMaid.posZ - this.lastPosZ;
			double movedHorizontalSq = (dX * dX) + (dZ * dZ);
			
			if (movedHorizontalSq < 0.05D) {
				this.realStuckCount++;
				System.out.println("[LMR-COLLECT-DEBUG] 捡物品卡死警告等级: " + this.realStuckCount);
			} else {
				this.realStuckCount = 0;
			}
			
			this.lastPosX = theMaid.posX;
			this.lastPosZ = theMaid.posZ;
			this.checkStuckTimer = 0;
			
			if (this.realStuckCount >= 3) {
				System.out.println("[LMR-COLLECT-DEBUG] 确认卡死！强行拿走目标物品！");
				if (this.targetItem != null && !this.targetItem.isDead) {
					this.targetItem.setNoPickupDelay();
					this.targetItem.setPosition(theMaid.posX, theMaid.posY, theMaid.posZ);
				}
				this.theMaid.getNavigator().clearPath();
			}
		}
	}

	public boolean canEntityItemBeSeen(Entity entity) {
		return VectorUtil.canMoveThrough(theMaid, 0D, MathHelper.floor(entity.posX), MathHelper.floor(entity.posY), MathHelper.floor(entity.posZ), false, true, false);
	}
}
