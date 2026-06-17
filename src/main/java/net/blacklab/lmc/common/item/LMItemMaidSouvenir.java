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
import net.minecraftforge.fml.common.registry.EntityEntry;
import net.minecraftforge.fml.common.registry.EntityEntryBuilder;
import net.minecraftforge.event.RegistryEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;

@EventBusSubscriber
public class LMItemMaidSouvenir extends Item {
	
	@SubscribeEvent
	public static void registerEntities(RegistryEvent.Register<EntityEntry> event) {
		event.getRegistry().register(EntityEntryBuilder.create()
			.entity(LMItemMaidSouvenir.EntityItemMaidSouvenir.class)
			.id(new ResourceLocation("lmr", "maid_souvenir_entity"), 114514)
			.name("maid_souvenir_entity")
			.tracker(64, 20, true)
			.build());
	}

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
					// 🌟 已修复：销毁超时提示接入多语言文件
					player.sendMessage(new net.minecraft.util.text.TextComponentTranslation("message.lmr.souvenir.crush_timeout"));
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
			NBTTagCompound nbt = stack.getTagCompound();
			if (nbt.hasKey("maid_owner")) {
				tooltip.add("Owner : " + nbt.getString("maid_owner"));
			}
			if (nbt.hasKey("maid_name")) {
				tooltip.add("Maid : " + nbt.getString("maid_name"));
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


    public static class EntityItemMaidSouvenir extends EntityItem {
        
        public EntityItemMaidSouvenir(World worldIn, double x, double y, double z, ItemStack stack) {
            super(worldIn, x, y, z, stack);
            
            // 【阵亡信标逻辑】：在遗物生成的瞬间，向主人发送救援坐标
            if (!worldIn.isRemote && !stack.isEmpty() && stack.hasTagCompound()) {
                NBTTagCompound nbt = stack.getTagCompound();
                
                if (!nbt.hasKey("death_notified") && nbt.hasKey("maid_owner")) {
                    nbt.setBoolean("death_notified", true);
                    
                    String ownerStr = nbt.getString("maid_owner");
                    
                    // 🌟 已修复：通过 TextComponent 处理未知女仆的本地化
                    Object maidNameObj = nbt.hasKey("maid_name") ? nbt.getString("maid_name") : new net.minecraft.util.text.TextComponentTranslation("message.lmr.souvenir.unknown_maid");
                    
                    EntityPlayer player = null;
                    try {
                        player = worldIn.getPlayerEntityByUUID(java.util.UUID.fromString(ownerStr));
                    } catch (Exception e) {
                        player = worldIn.getPlayerEntityByName(ownerStr);
                    }

                    if (player != null) {
                        int dim = worldIn.provider.getDimension();
                        
                        // 🌟 已修复：维度名称彻底通过本地化语言包翻译，杜绝 Java 内硬编码
                        Object dimComponent;
                        if (dim == 0) {
                            dimComponent = new net.minecraft.util.text.TextComponentTranslation("message.lmr.dim.0");
                        } else if (dim == -1) {
                            dimComponent = new net.minecraft.util.text.TextComponentTranslation("message.lmr.dim.-1");
                        } else if (dim == 1) {
                            dimComponent = new net.minecraft.util.text.TextComponentTranslation("message.lmr.dim.1");
                        } else {
                            dimComponent = new net.minecraft.util.text.TextComponentTranslation("message.lmr.dim.unknown", dim);
                        }
                        
                        // 发送完全本地化的阵亡信标通知（括号层级完全闭合正确）
                        player.sendMessage(new net.minecraft.util.text.TextComponentTranslation(
                                "message.lmr.souvenir.death_notice", 
                                maidNameObj, 
                                dimComponent, 
                                (int)x, 
                                (int)y, 
                                (int)z
                        ));
                    }
                }
            }
        }

        public EntityItemMaidSouvenir(World worldIn) {
            super(worldIn);
        }
        
        @Override
        @SideOnly(Side.CLIENT)
        public boolean isInRangeToRenderDist(double distance) {
            return true; 
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
                
                if (!this.isGlowing()) {
                    this.setGlowing(true);
                }

                int lavaDistance = -1;
                BlockPos currentPos = new BlockPos(this);
                for (int i = 0; i <= 3; i++) {
                    if (this.world.getBlockState(currentPos.down(i)).getMaterial() == net.minecraft.block.material.Material.LAVA) {
                        lavaDistance = i;
                        break;
                    }
                }

                if (lavaDistance >= 0 || this.isInLava()) {
                    this.setNoGravity(true);
                    
                    this.motionX *= 0.8D;
                    this.motionZ *= 0.8D;
                    
                    this.rotationYaw = (this.rotationYaw + 3.0F) % 360.0F;

                    double targetHoverY = Math.sin(this.ticksExisted * 0.1D) * 0.015D;
                    
                    if (this.isInLava() || lavaDistance == 0) {
                        targetHoverY += 0.04D; 
                    } else if (lavaDistance == 1) {
                        targetHoverY += 0.01D; 
                    } else if (lavaDistance == 3) {
                        targetHoverY -= 0.02D; 
                    }
                    
                    this.motionY = targetHoverY;
                    
                } else {
                    this.setNoGravity(false);
                }

            } else {
                this.isImmuneToFire = false;
                this.setNoGravity(false);
                if (this.isGlowing() && !LMRConfig.cfg_general_item_glowing) {
                    this.setGlowing(false);
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
                        safeItem.motionX = 0; safeItem.motionY = 0; safeItem.motionZ = 0;
                        safeItem.setDefaultPickupDelay();
                        if (LMRConfig.cfg_general_item_glowing) safeItem.setGlowing(true);
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
