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
 * メイドの土産 (真·不朽绑定与主人超度版)
 */
public class LMItemMaidSouvenir extends Item {

	public LMItemMaidSouvenir() {
		this.setMaxStackSize(1);
	}

	/**
	 * メイドさんを生成する
	 */
	@Override
	public EnumActionResult onItemUse(EntityPlayer player, World worldIn, BlockPos pos, EnumHand hand, EnumFacing facing, float hitX, float hitY, float hitZ) {
		ItemStack stack = player.getHeldItem(hand);
		
		if (!stack.isEmpty() && stack.getItem() == LMItems.MAID_SOUVENIR && stack.hasTagCompound()) {
			BlockPos position = pos.offset(facing);
			double x = position.getX() + 0.5;
			double y = position.getY();
			double z = position.getZ() + 0.5;
			
			Entity entity = LittleMaidHelper.spawnEntityFromItemStack(stack, worldIn, x, y, z);
			
			if (entity != null) {
				EntityLittleMaid maid = (EntityLittleMaid) entity;
				maid.clearMaidContractLimit();
				maid.setMaidFlags(true, dataWatch_Flags_remainsContract);
			}
			
			player.setHeldItem(hand, ItemStack.EMPTY);
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
    // 专属不死实体类 (动态特权版：有主人则绝对不朽，无主人则普通物品)
    // ====================================================================
    public static class EntityItemMaidSouvenir extends EntityItem {
        
        private int crushProgress = 0;
        // 【新增】：记录上一次成功点击的时间戳（以实体存活的 Tick 为单位）
        private int lastCrushTick = 0; 
        
        public EntityItemMaidSouvenir(World worldIn, double x, double y, double z, ItemStack stack) {
            super(worldIn, x, y, z, stack);
        }

        public EntityItemMaidSouvenir(World worldIn) {
            super(worldIn);
        }

        private boolean hasValidOwner() {
            ItemStack stack = this.getItem();
            return !stack.isEmpty() && stack.hasTagCompound() && stack.getTagCompound().hasKey("maid_owner");
        }

        @Override
        public void onUpdate() {
            super.onUpdate();
            if (hasValidOwner()) {
                this.isImmuneToFire = true;
                this.lifespan = Integer.MAX_VALUE; 
            } else {
                this.isImmuneToFire = false;
            }
        }

        @Override
        public boolean processInitialInteract(EntityPlayer player, EnumHand hand) {
            if (!this.world.isRemote && player.isSneaking() && hand == EnumHand.MAIN_HAND) {
                ItemStack stack = this.getItem();
                
                if (stack.hasTagCompound() && stack.getTagCompound().hasKey("maid_owner")) {
                    String ownerTarget = stack.getTagCompound().getString("maid_owner");
                    
                    if (player.getUniqueID().toString().equals(ownerTarget) || player.getName().equals(ownerTarget)) {
                        
                        int currentTick = this.ticksExisted;
                        
                        // 【限时重置系统】：如果距离上次点击超过 10 秒 (200 Ticks)，进度清零
                        if (this.crushProgress > 0 && (currentTick - this.lastCrushTick > 200)) {
                            this.crushProgress = 0;
                            // 发送一条黄色的中断提示
                            player.sendMessage(new net.minecraft.util.text.TextComponentString("§e[系统] 销毁操作超时，进度已重置。"));
                        }
                        
                        // 【防长按/防连点系统】：如果两次有效点击间隔小于 0.5 秒 (10 Ticks)，直接拦截并无视
                        if (this.crushProgress > 0 && (currentTick - this.lastCrushTick < 10)) {
                            return true; 
                        }
                        
                        // 记录本次有效点击的时间戳，并增加进度
                        this.lastCrushTick = currentTick;
                        this.crushProgress++;
                        
                        if (this.crushProgress >= 3) {
                            this.world.playSound(null, this.posX, this.posY, this.posZ, 
                                    net.minecraft.init.SoundEvents.BLOCK_GLASS_BREAK, 
                                    net.minecraft.util.SoundCategory.PLAYERS, 1.0F, 0.5F);
                            
                            if (this.world instanceof WorldServer) {
                                ((WorldServer)this.world).spawnParticle(
                                    net.minecraft.util.EnumParticleTypes.SMOKE_LARGE, 
                                    this.posX, this.posY + 0.5D, this.posZ, 
                                    25, 0.2D, 0.2D, 0.2D, 0.0D);
                            }
                            
                            net.minecraft.util.text.TextComponentTranslation doneMsg = new net.minecraft.util.text.TextComponentTranslation("message.lmr.souvenir.crush_done");
                            doneMsg.getStyle().setColor(net.minecraft.util.text.TextFormatting.DARK_GRAY);
                            player.sendMessage(doneMsg);
                            
                            this.setDead();
                        } else {
                            this.world.playSound(null, this.posX, this.posY, this.posZ, 
                                    net.minecraft.init.SoundEvents.ENTITY_ZOMBIE_ATTACK_DOOR_WOOD, 
                                    net.minecraft.util.SoundCategory.PLAYERS, 0.5F, 1.5F);
                            
                            int leftClicks = 3 - this.crushProgress;
                            net.minecraft.util.text.TextComponentTranslation warnMsg = new net.minecraft.util.text.TextComponentTranslation("message.lmr.souvenir.crush_warning", leftClicks);
                            warnMsg.getStyle().setColor(net.minecraft.util.text.TextFormatting.RED);
                            player.sendMessage(warnMsg);
                        }
                        return true; 
                    }
                }
            }
            return super.processInitialInteract(player, hand);
        }

        @Override
        public boolean attackEntityFrom(DamageSource source, float amount) {
            if (source == DamageSource.OUT_OF_WORLD) {
                return super.attackEntityFrom(source, amount);
            }
            if (hasValidOwner()) {
                return false; 
            }
            return super.attackEntityFrom(source, amount);
        }

        @Override
        protected void outOfWorld() {
            if (hasValidOwner()) {
                if (!this.world.isRemote) {
                    WorldServer overworld = DimensionManager.getWorld(0);
                    if (overworld != null) {
                        BlockPos spawnPos = overworld.getSpawnPoint();
                        
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
                    this.setDead();
                }
            } else {
                super.outOfWorld();
            }
        }
    }
}
