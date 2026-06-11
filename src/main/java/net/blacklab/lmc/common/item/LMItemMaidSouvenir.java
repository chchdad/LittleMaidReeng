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
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ActionResult;
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
 * メイドの土産 (在手捏碎超度 + 强制视距寻主光柱 + 真实岩浆悬浮版)
 */
public class LMItemMaidSouvenir extends Item {

	public LMItemMaidSouvenir() {
		this.setMaxStackSize(1);
	}

	@Override
	public ActionResult<ItemStack> onItemRightClick(World worldIn, EntityPlayer playerIn, EnumHand handIn) {
		ItemStack stack = playerIn.getHeldItem(handIn);
		if (playerIn.isSneaking() && handIn == EnumHand.MAIN_HAND) {
			handleCrush(stack, playerIn, worldIn);
			return new ActionResult<>(EnumActionResult.SUCCESS, stack);
		}
		return new ActionResult<>(EnumActionResult.PASS, stack);
	}

	@Override
	public EnumActionResult onItemUse(EntityPlayer player, World worldIn, BlockPos pos, EnumHand hand, EnumFacing facing, float hitX, float hitY, float hitZ) {
		ItemStack stack = player.getHeldItem(hand);
		
		if (player.isSneaking() && hand == EnumHand.MAIN_HAND) {
			handleCrush(stack, player, worldIn);
			return EnumActionResult.SUCCESS; 
		}
		
		if (!stack.isEmpty() && stack.getItem() == LMItems.MAID_SOUVENIR && stack.hasTagCompound()) {
			BlockPos position = pos.offset(facing);
			Entity entity = LittleMaidHelper.spawnEntityFromItemStack(stack, worldIn, position.getX() + 0.5, position.getY(), position.getZ() + 0.5);
			
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

	private void handleCrush(ItemStack stack, EntityPlayer player, World world) {
		if (world.isRemote) return;

		if (stack.hasTagCompound() && stack.getTagCompound().hasKey("maid_owner")) {
			String ownerTarget = stack.getTagCompound().getString("maid_owner");
			
			if (player.getUniqueID().toString().equals(ownerTarget) || player.getName().equals(ownerTarget)) {
				
				long currentTime = world.getTotalWorldTime();
				NBTTagCompound nbt = stack.getTagCompound();
				long lastTime = nbt.getLong("lmr_crush_time");
				int progress = nbt.getInteger("lmr_crush_progress");

				if (progress > 0 && (currentTime - lastTime > 200)) {
					progress = 0;
					player.sendMessage(new net.minecraft.util.text.TextComponentString("§e[系统] 销毁操作超时，进度已重置。"));
				}

				if (progress > 0 && (currentTime - lastTime < 10)) {
					return; 
				}

				progress++;
				nbt.setLong("lmr_crush_time", currentTime);
				nbt.setInteger("lmr_crush_progress", progress);

				if (progress >= 3) {
					world.playSound(null, player.posX, player.posY, player.posZ, 
							net.minecraft.init.SoundEvents.BLOCK_GLASS_BREAK, 
							net.minecraft.util.SoundCategory.PLAYERS, 1.0F, 0.5F);
					
					if (world instanceof WorldServer) {
						((WorldServer)world).spawnParticle(
							net.minecraft.util.EnumParticleTypes.SMOKE_LARGE, 
							player.posX, player.posY + 1.0D, player.posZ, 
							25, 0.2D, 0.2D, 0.2D, 0.0D);
					}
					
					net.minecraft.util.text.TextComponentTranslation doneMsg = new net.minecraft.util.text.TextComponentTranslation("message.lmr.souvenir.crush_done");
					doneMsg.getStyle().setColor(net.minecraft.util.text.TextFormatting.DARK_GRAY);
					player.sendMessage(doneMsg);
					
					stack.setCount(0); 
				} else {
					// 修复 1：修正了 SoundCategory 的包名路径
					world.playSound(null, player.posX, player.posY, player.posZ, 
							net.minecraft.init.SoundEvents.ENTITY_ZOMBIE_ATTACK_DOOR_WOOD, 
							net.minecraft.util.SoundCategory.PLAYERS, 0.5F, 1.5F);
					
					int leftClicks = 3 - progress;
					net.minecraft.util.text.TextComponentTranslation warnMsg = new net.minecraft.util.text.TextComponentTranslation("message.lmr.souvenir.crush_warning", leftClicks);
					warnMsg.getStyle().setColor(net.minecraft.util.text.TextFormatting.RED);
					player.sendMessage(warnMsg);
				}
			}
		}
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
    // 专属不死实体类 (光柱无视视距强制渲染 + 真实岩浆Bobbing悬浮 + 不朽 + 虚空转生)
    // ====================================================================
    public static class EntityItemMaidSouvenir extends EntityItem {
        
        public EntityItemMaidSouvenir(World worldIn, double x, double y, double z, ItemStack stack) {
            super(worldIn, x, y, z, stack);
            // 修复 2：移除了私有变量 renderDistanceWeight 的访问
        }

        public EntityItemMaidSouvenir(World worldIn) {
            super(worldIn);
        }
        
        // 修复 3：通过重写 isInRangeToRenderDist 方法，彻底解除实体的视距渲染限制
        @Override
        @SideOnly(Side.CLIENT)
        public boolean isInRangeToRenderDist(double distance) {
            return true; // 强制在任何距离下都保持实体渲染状态，配合光柱效果
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
                
                // 【岩浆真实悬浮系统】：直接对抗重力引擎
                if (this.isInLava()) {
                    // 提供稳定的向上浮力，原版会每 Tick -0.04D，我们直接覆盖为正数
                    this.motionY = 0.05D;
                    // 水平方向增加强大阻力，防止乱飘
                    this.motionX *= 0.8D;
                    this.motionZ *= 0.8D;
                }

                // 【远程寻主光柱系统】
                if (this.world.isRemote) {
                    spawnBeaconParticles();
                }
            } else {
                this.isImmuneToFire = false;
            }
        }

		@SideOnly(Side.CLIENT)
		private void spawnBeaconParticles() {
			net.minecraft.client.entity.EntityPlayerSP clientPlayer = net.minecraft.client.Minecraft.getMinecraft().player;
			if (clientPlayer != null) {
				String ownerTarget = this.getItem().getTagCompound().getString("maid_owner");
				
				// 判定主人，并且距离大于 8 格 (64.0D 平方) 时触发
				if ((clientPlayer.getUniqueID().toString().equals(ownerTarget) || clientPlayer.getName().equals(ownerTarget)) 
					&& this.getDistanceSq(clientPlayer) > 64.0D) {
					
					// 增加粒子密度，生成极其显眼的粉紫色光束
					for (int i = 0; i < 8; i++) {
						double pY = this.posY + (this.world.rand.nextDouble() * 30.0D);
						
						// 第二个参数设为 true，告诉引擎忽略视距，强制渲染这束光！
						this.world.spawnParticle(net.minecraft.util.EnumParticleTypes.SPELL_MOB, true, 
							this.posX + (this.world.rand.nextDouble() - 0.5D) * 0.1D, 
							pY, 
							this.posZ + (this.world.rand.nextDouble() - 0.5D) * 0.1D, 
							1.0D, 0.3D, 0.9D);
					}
				}
			}
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
