package net.blacklab.lmr.entity.littlemaid.ai.target;

import java.util.Comparator;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;

public class ComparatorLMNearestAttackableTargetSorter<T extends EntityLivingBase> implements Comparator<EntityLivingBase> {

	private Entity theEntity;

	public ComparatorLMNearestAttackableTargetSorter(Entity par1Entity) {
		this.theEntity = par1Entity;
	}

	        public int compareDistanceSq(EntityLivingBase par1Entity, EntityLivingBase par2Entity) {
                // ====== 修改 1：绝对护主优先级 ======
                if (this.theEntity instanceof net.minecraft.entity.player.EntityPlayer) {
                        net.minecraft.entity.player.EntityPlayer master = (net.minecraft.entity.player.EntityPlayer) this.theEntity;
                        
                        // 先判断实体是否具有 AI (EntityLiving)，再强转获取它的攻击目标
                        boolean p1AttackingMaster = (par1Entity instanceof net.minecraft.entity.EntityLiving) && (((net.minecraft.entity.EntityLiving) par1Entity).getAttackTarget() == master);
                        boolean p2AttackingMaster = (par2Entity instanceof net.minecraft.entity.EntityLiving) && (((net.minecraft.entity.EntityLiving) par2Entity).getAttackTarget() == master);

                        if (p1AttackingMaster && !p2AttackingMaster) return -1;
                        if (!p1AttackingMaster && p2AttackingMaster) return 1;
                }

                // ====== 修改 2：次级优先级（就主人近原则） ======
                double var3 = this.theEntity.getDistanceSq(par1Entity);
                double var5 = this.theEntity.getDistanceSq(par2Entity);
                return var3 < var5 ? -1 : (var3 > var5 ? 1 : 0);
        }

	@Override
	public int compare(EntityLivingBase par1Obj, EntityLivingBase par2Obj) {
		return compareDistanceSq(par1Obj, par2Obj);
	}

	public void setEntity(Entity pEntity) {
		theEntity = pEntity;
	}

}
