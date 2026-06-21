package net.blacklab.lmr.entity.littlemaid.ai.attack;

import net.blacklab.lmr.entity.littlemaid.EntityLittleMaid;
import net.blacklab.lmr.entity.littlemaid.ai.IEntityAILM;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.ai.EntityAIBase;
import net.minecraft.item.ItemAxe;
import net.minecraft.item.ItemStack;

/**
 * 狂战士的独立测试 AI
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
		if (!fEnable || theMaid.isMaidWait()) return false;
		
		//双手双斧判定
		ItemStack mainHand = theMaid.getHeldItemMainhand();
		ItemStack offHand = theMaid.getHeldItemOffhand();
		
		boolean hasMainAxe = !mainHand.isEmpty() && mainHand.getItem() instanceof ItemAxe;
		boolean hasOffAxe = !offHand.isEmpty() && offHand.getItem() instanceof ItemAxe;
		
		// 只有主手和副手同时拿着斧头，才会狂战士！单手斧不触发！
		if (!(hasMainAxe && hasOffAxe)) {
			return false;
		}

		EntityLivingBase target = theMaid.getAttackTarget();
		return target != null && target.isEntityAlive();
	}
	
	@Override
	public void startExecuting() {
		theMaid.getNavigator().clearPath();
	}

	@Override
	public void updateTask() {
		logLimiter++;
		if (logLimiter % 20 == 0) {
			System.err.println("[LMR-BERSERKER-DEBUG] 狂战士连通，正在测试。");
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
