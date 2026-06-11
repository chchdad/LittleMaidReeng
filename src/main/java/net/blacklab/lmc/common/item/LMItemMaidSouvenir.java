    public static class EntityItemMaidSouvenir extends EntityItem {
        
        public EntityItemMaidSouvenir(World worldIn, double x, double y, double z, ItemStack stack) {
            super(worldIn, x, y, z, stack);
        }

        public EntityItemMaidSouvenir(World worldIn) {
            super(worldIn);
        }
        
        @Override
        @SideOnly(Side.CLIENT)
        public boolean isInRangeToRenderDist(double distance) {
            return true; // 强制渲染
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

                // 探测自身以及下方 3 格内是否有岩浆
                int lavaDistance = -1;
                BlockPos currentPos = new BlockPos(this);
                for (int i = 0; i <= 3; i++) {
                    if (this.world.getBlockState(currentPos.down(i)).getMaterial() == net.minecraft.block.material.Material.LAVA) {
                        lavaDistance = i;
                        break;
                    }
                }

                if (lavaDistance >= 0 || this.isInLava()) {
                    // 1. 彻底关闭原版重力，拔掉“蹦极”的根源
                    this.setNoGravity(true);
                    
                    // 2. 空间阻尼刹车，防止被水冲走或被怪踢飞
                    this.motionX *= 0.8D;
                    this.motionZ *= 0.8D;
                    
                    // 3. 神器自转效果 
                    this.rotationYaw = (this.rotationYaw + 3.0F) % 360.0F;

                    // 4. 恶魂呼吸悬浮逻辑 (只给极其微弱的浮力/下压力调整高度)
                    double targetHoverY = Math.sin(this.ticksExisted * 0.1D) * 0.015D;
                    
                    if (this.isInLava() || lavaDistance == 0) {
                        targetHoverY += 0.04D; // 淹没在岩浆里？温柔地推上来
                    } else if (lavaDistance == 1) {
                        targetHoverY += 0.01D; // 稍微拉高点，保持 2 格最佳观赏高度
                    } else if (lavaDistance == 3) {
                        targetHoverY -= 0.02D; // 飞太高了？缓缓降下来
                    }
                    
                    this.motionY = targetHoverY;
                    
                } else {
                    // ====== 离开了岩浆区域，恢复正常物理引擎 ======
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

        // 虚空防坠落逻辑保留
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
