package net.blacklab.lmr.entity.littlemaid.ai.attack;

import net.blacklab.lmr.entity.littlemaid.EntityLittleMaid;
import net.blacklab.lmr.entity.littlemaid.ai.IEntityAILM;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.ai.EntityAIBase;
import net.minecraft.item.ItemAxe;
import net.minecraft.item.ItemStack;

/**
 * 狂战士的独立测试 AI (双手双斧严格判定 + 强制供电全局接管)
 */
public class EntityAILMAttackBerserker extends EntityAIBase implements IEntityAILM {

	protected boolean fEnable;
	protected EntityLittleMaid theMaid;
	private int logLimiter = 0;

	public EntityAILMAttackBerserker(EntityLittleMaid par1EntityLittleMaid) {
		theMaid = par1EntityLittleMaid;
		setMutexBits(3); // 占用寻路和身体动作，让她罚站
	}

	@Override
	public boolean shouldExecute() {
		// 删除了 !fEnable，彻底无视总控台的开关！
		if (theMaid.isMaidWait()) return false;
		
		// 极其严格的“双手双斧”判定！
		ItemStack mainHand = theMaid.getHeldItemMainhand();
		ItemStack offHand = theMaid.getHeldItemOffhand();
		
		boolean hasMainAxe = !mainHand.isEmpty() && mainHand.getItem() instanceof ItemAxe;
		boolean hasOffAxe = !offHand.isEmpty() && offHand.getItem() instanceof ItemAxe;
		
		// 只有主手和副手同时拿着斧头，才会激活狂战士！
		if (!(hasMainAxe && hasOffAxe)) {
			return false;
		}

		EntityLivingBase target = theMaid.getAttackTarget();
		return target != null && target.isEntityAlive();
	}
	
	@Override
	public void startExecuting() {
		// 一旦接管，立刻强行刹车，打断那个原版的“跳扑”动作！
		theMaid.getNavigator().clearPath();
		theMaid.motionX = 0;
		theMaid.motionZ = 0;
	}

	@Override
	public void updateTask() {
		logLimiter++;
		if (logLimiter % 20 == 0) {
			System.err.println("[LMR-BERSERKER-DEBUG]  狂战士连通！");
		}
		
		EntityLivingBase target = theMaid.getAttackTarget();
		if (target != null) {
			theMaid.getLookHelper().setLookPositionWithEntity(target, 30F, 30F);
		}
	}

	@Override
	public void setEnable(boolean pFlag) { fEnable = pFlag; }
	@Override
	public boolean getEnable() { return fEnable; }
}
