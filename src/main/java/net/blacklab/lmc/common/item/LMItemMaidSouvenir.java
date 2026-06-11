package net.blacklab.lmc.common.item;

import static net.blacklab.lmr.util.Statics.dataWatch_Flags_remainsContract;

import java.util.List;

import javax.annotation.Nullable;

import net.blacklab.lmc.common.helper.LittleMaidHelper;
import net.blacklab.lmr.LittleMaidReengaged.LMItems;
import net.blacklab.lmr.config.LMRConfig;
import net.blacklab.lmr.entity.littlemaid.EntityLittleMaid;
import net.minecraft.client.resources.I18n;
import net.minecraft.client.util.ITooltipFlag;
import net.minecraft.entity.Entity;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.EnumRarity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.DamageSource;
import net.minecraft.util.EnumActionResult;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.DimensionManager;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

/**
 * メイドの土産 (重制防丢防爆绝对不朽版)
 */
public class LMItemMaidSouvenir extends Item {

	public LMItemMaidSouvenir() {
		//クリエイティブタブには登録しない
		this.setMaxStackSize(1);
	}

	/**
	 * メイドさんを生成する
	 */
	@Override
	public EnumActionResult onItemUse(EntityPlayer player, World worldIn, BlockPos pos, EnumHand hand, EnumFacing facing, float hitX, float hitY, float hitZ) {
		
		ItemStack stack = player.getHeldItem(hand);
		
		if (!stack.isEmpty() 
				&& stack.getItem() == LMItems.MAID_SOUVENIR 
				&& stack.hasTagCompound()) {
			
			BlockPos position = pos.offset(facing);
			double x = position.getX() + 0.5;
			double y = position.getY();
			double z = position.getZ() + 0.5;
			
			//メイドさんのスポーン
			Entity entity = LittleMaidHelper.spawnEntityFromItemStack(stack, worldIn, x, y, z);
			
			//ストライキ状態にする
			if (entity != null) {
				EntityLittleMaid maid = (EntityLittleMaid) entity;
				//契約時間をリセット
				maid.clearMaidContractLimit();
				//ストライキを設定する
				maid.setMaidFlags(true, dataWatch_Flags_remainsContract);
			}
			
			player.setHeldItem(hand, ItemStack.EMPTY);
			
			//1秒のCoolDownTimeを設定
			player.getCooldownTracker().setCooldown(this, 20);
			
			return EnumActionResult.SUCCESS;
		}
		
        return EnumActionResult.PASS;
    }
	
	@Override
	@SideOnly(Side.CLIENT)
    public void addInformation(ItemStack stack, @Nullable World worldIn, List<String> tooltip, ITooltipFlag flagIn) {
		
		tooltip.add(TextFormatting.LIGHT_PURPLE + I18n.format("item.maid_souvenir.info"));
		
		if (stack.hasTagCompound()) {
			if (stack.getTagCompound().hasKey("maid_owner")) {
				tooltip.add("Owner : " + stack.getTagCompound().getString("maid_owner"));
			}
			if (stack.getTagCompound().hasKey("maid_name")) {
				tooltip.add("Maid : " + stack.getTagCompound().getString("maid_name"));
			}
		}
    }
	
	@Override
	public boolean hasCustomEntity(ItemStack stack) {
		return true;
	}
	
	@Override
	@Nullable
    public Entity createEntity(World world, Entity location, ItemStack itemstack) {
        // 使用我们独家定制的绝对不朽实体替换掉原本的 LMEntityItemAntiDamage
		EntityItemMaidSouvenir entity = new EntityItemMaidSouvenir(world, location.posX, location.posY, location.posZ, itemstack);
		entity.setDefaultPickupDelay();

		entity.motionX = location.motionX;
		entity.motionY = location.motionY;
		entity.motionZ = location.motionZ;
		
		if (LMRConfig.cfg_general_item_glowing) {
			entity.setGlowing(true);
		}
		
        return entity;
    }
	
	@Override
	@SideOnly(Side.CLIENT)
    public boolean hasEffect(ItemStack stack) {
        return stack.hasTagCompound();
    }
	
	@Override
	public EnumRarity getRarity(ItemStack stack) {
        return stack.hasTagCompound() ? EnumRarity.RARE : EnumRarity.COMMON;
    }

    // ====================================================================
    // 专属的不死掉落物实体类 (免疫一切伤害 + 虚空秽土转生)
    // ====================================================================
    public static class EntityItemMaidSouvenir extends EntityItem {
        
        public EntityItemMaidSouvenir(World worldIn, double x, double y, double z, ItemStack stack) {
            super(worldIn, x, y, z, stack);
            this.isImmuneToFire = true;
            this.setEntityInvulnerable(true);
            this.lifespan = Integer.MAX_VALUE; // 永不消失
        }

        public EntityItemMaidSouvenir(World worldIn) {
            super(worldIn);
            this.isImmuneToFire = true;
            this.setEntityInvulnerable(true);
            this.lifespan = Integer.MAX_VALUE;
        }

        // 【智能防御系统】：拦截意外伤害，但允许刻意销毁
        @Override
        public boolean attackEntityFrom(DamageSource source, float amount) {
            
            // 1. 允许管理员使用 /kill 指令强制清理 (指令发出的也是 OUT_OF_WORLD 伤害)
            // 放心，这不会干扰虚空传送，因为坠入虚空触发的是 outOfWorld() 方法，而不是这里的伤害事件
            if (source == DamageSource.OUT_OF_WORLD) {
                return super.attackEntityFrom(source, amount);
            }
            
            // 2. 允许玩家使用【坠落的铁砧】进行物理超度
            if (source == DamageSource.ANVIL) {
                System.out.println("[LMR-DEATH-DEBUG] 遗物已被铁砧物理超度，彻底销毁！");
                return super.attackEntityFrom(source, amount);
            }
            
            // 其他一切意外伤害（岩浆、爆炸、仙人掌、火烧等）全部免疫！
            return false; 
		}

        // 跨界秽土转生：掉出世界底部时的处理
        @Override
        protected void outOfWorld() {
            if (!this.world.isRemote) {
                // 强制获取主世界（维度 0）
                WorldServer overworld = DimensionManager.getWorld(0);
                if (overworld != null) {
                    BlockPos spawnPos = overworld.getSpawnPoint();
                    
                    // 生成新遗物。务必使用 copy() 切断内存指针联系！
                    EntityItemMaidSouvenir safeItem = new EntityItemMaidSouvenir(
                        overworld, 
                        spawnPos.getX() + 0.5D, 
                        spawnPos.getY() + 1.5D, 
                        spawnPos.getZ() + 0.5D, 
                        this.getItem().copy() 
                    );
                    
                    safeItem.motionX = 0; 
                    safeItem.motionY = 0; 
                    safeItem.motionZ = 0;
                    safeItem.setDefaultPickupDelay();
                    
                    if (LMRConfig.cfg_general_item_glowing) {
                        safeItem.setGlowing(true);
                    }
                    
                    overworld.spawnEntity(safeItem);
                }
                
                // 安全抹除掉入虚空的旧实体，引擎会自动在 Tick 末尾回收它，不会引发 NPE
                this.setDead();
            }
        }
    }
}
