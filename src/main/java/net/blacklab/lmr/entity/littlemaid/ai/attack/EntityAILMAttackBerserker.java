package net.blacklab.lmr.entity.littlemaid.ai.attack;

import net.blacklab.lmr.entity.littlemaid.EntityLittleMaid;
import net.blacklab.lmr.entity.littlemaid.ai.IEntityAILM;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.ai.EntityAIBase;
import net.minecraft.item.ItemAxe;
import net.minecraft.item.ItemStack;

/**
 * 狂战士的独立测试 AI (双手双斧严格判定 + 脑电波实时诊断)
 */
public class EntityAILMAttackBerserker extends EntityAIBase implements IEntityAILM {

	protected boolean fEnable;
	protected EntityLittleMaid theMaid;
	private int tickCounter = 0;

	public EntityAILMAttackBerserker(EntityLittleMaid par1EntityLittleMaid) {
		theMaid = par1EntityLittleMaid;
		setMutexBits(3); 
	}

	@Override
	public boolean shouldExecute() {
		tickCounter++;
		
		ItemStack mainHand = theMaid.getHeldItemMainhand();
		ItemStack offHand = theMaid.getHeldItemOffhand();
		
		boolean hasMainAxe = !mainHand.isEmpty() && mainHand.getItem() instanceof ItemAxe;
		boolean hasOffAxe = !offHand.isEmpty() && offHand.getItem() instanceof ItemAxe;
		
		//  脑电波探针：只要主手有斧头，每2秒强制汇报一次她的视觉和判定状态！
		if (tickCounter % 40 == 0 && hasMainAxe) {
			String mName = mainHand.isEmpty() ? "空" : mainHand.getDisplayName();
			String oName = offHand.isEmpty() ? "空" : offHand.getDisplayName();
			EntityLivingBase target = theMaid.getAttackTarget();
			String tName = target != null ? target.getName() : "无目标";
			
			System.err.println("[LMR-BERSERKER-CHECK] 脑电波扫描... 主手:[" + mName + "] 副手:[" + oName + "] 仇恨目标:[" + tName + "]");
			
			if (!hasOffAxe) {
				System.err.println("[LMR-BERSERKER-CHECK] 拦截: 副手不是斧头！狂战士血脉被压制，她现在是个只想砍树的伐木工");
			} else if (target == null) {
				System.err.println("[LMR-BERSERKER-CHECK] 拦截: 双斧已就绪，但没锁定敌人(可能模式未切换到 Bloodsucker)");
			}
		}

		if (theMaid.isMaidWait()) return false;
		
		// 只有主手和副手同时拿着斧头，才会激活狂战士之血！
		if (!(hasMainAxe && hasOffAxe)) {
			return false;
		}

		EntityLivingBase target = theMaid.getAttackTarget();
		return target != null && target.isEntityAlive();
	}
	
	@Override
	public void startExecuting() {
		theMaid.getNavigator().clearPath();
		theMaid.motionX = 0;
		theMaid.motionZ = 0;
		System.err.println("[LMR-BERSERKER-DEBUG]  双斧就位，进入罚站模式！");
	}

	@Override
	public void updateTask() {
		if (tickCounter % 20 == 0) {
			System.err.println("[LMR-BERSERKER-DEBUG]  狂战士：盯着猎物发呆");
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
