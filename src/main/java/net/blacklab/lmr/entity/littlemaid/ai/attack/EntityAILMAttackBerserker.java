package net.blacklab.lmr.entity.littlemaid.ai.attack;

import net.blacklab.lmr.entity.littlemaid.EntityLittleMaid;
import net.blacklab.lmr.entity.littlemaid.ai.IEntityAILM;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.ai.EntityAIBase;
import net.minecraft.item.ItemAxe;
import net.minecraft.item.ItemStack;

/**
 * 狂战士独立 AI (全链路曳光弹监控版)
 */
public class EntityAILMAttackBerserker extends EntityAIBase implements IEntityAILM {

	protected boolean fEnable;
	protected EntityLittleMaid theMaid;
	private int tickCounter = 0;

	public EntityAILMAttackBerserker(EntityLittleMaid par1EntityLittleMaid) {
		theMaid = par1EntityLittleMaid;
		setMutexBits(3);
		// 🌟 监控点 1：出生证明。只要 EntityLittleMaid.java 里 new 了它，启动游戏时必弹！
		System.err.println("[LMR-BERSERKER-TRACE] 🐣 狂战士大脑被实例化！宿主ID: " + (theMaid != null ? theMaid.getEntityId() : "幽灵"));
	}

	@Override
	public void setEnable(boolean pFlag) { 
		fEnable = pFlag; 
		// 🌟 监控点 2：电闸监控。看看切模式时，总控台有没有给它通电或断电
		System.err.println("[LMR-BERSERKER-TRACE] ⚡ 狂战士大脑电闸被拨动：当前状态 -> " + pFlag);
	}

	@Override
	public boolean getEnable() { return fEnable; }

	@Override
	public boolean shouldExecute() {
		tickCounter++;
		
		// 🌟 监控点 3：无条件心跳包。只要这串代码在任务列表里，每 3 秒（60刻）雷打不动报数！
		if (tickCounter % 60 == 0) {
			ItemStack mainHand = theMaid.getHeldItemMainhand();
			ItemStack offHand = theMaid.getHeldItemOffhand();
			String mName = mainHand.isEmpty() ? "空" : mainHand.getDisplayName();
			String oName = offHand.isEmpty() ? "空" : offHand.getDisplayName();
			
			System.err.println("[LMR-BERSERKER-TRACE] 💓 脑电波持续跳动中... 模式:[" + theMaid.getMaidModeString() + "] 主手:[" + mName + "] 副手:[" + oName + "]");
		}

		if (theMaid.isMaidWait()) return false;
		
		ItemStack mainHand = theMaid.getHeldItemMainhand();
		ItemStack offHand = theMaid.getHeldItemOffhand();
		
		boolean hasMainAxe = !mainHand.isEmpty() && mainHand.getItem() instanceof ItemAxe;
		boolean hasOffAxe = !offHand.isEmpty() && offHand.getItem() instanceof ItemAxe;
		
		if (!(hasMainAxe && hasOffAxe)) {
			return false; // 不是双斧，继续潜伏
		}

		EntityLivingBase target = theMaid.getAttackTarget();
		return target != null && target.isEntityAlive();
	}
	
	@Override
	public void startExecuting() {
		theMaid.getNavigator().clearPath();
		theMaid.motionX = 0;
		theMaid.motionZ = 0;
		System.err.println("[LMR-BERSERKER-TRACE] 💥💥💥 狂战士强制夺舍成功！准备大开杀戒！");
	}

	@Override
	public void updateTask() {
		if (tickCounter % 20 == 0) {
			System.err.println("[LMR-BERSERKER-TRACE] 🪓🩸 锁定目标！正在发呆！");
		}
		EntityLivingBase target = theMaid.getAttackTarget();
		if (target != null) {
			theMaid.getLookHelper().setLookPositionWithEntity(target, 30F, 30F);
		}
	}
}
