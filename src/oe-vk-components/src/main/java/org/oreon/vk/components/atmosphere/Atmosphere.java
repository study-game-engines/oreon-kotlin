package org.oreon.vk.components.atmosphere;

import static org.lwjgl.system.MemoryUtil.memAlloc;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_INDEX_BUFFER_BIT;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_VERTEX_BUFFER_BIT;
import static org.lwjgl.vulkan.VK10.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER;
import static org.lwjgl.vulkan.VK10.VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST;
import static org.lwjgl.vulkan.VK10.VK_SHADER_STAGE_FRAGMENT_BIT;
import static org.lwjgl.vulkan.VK10.VK_SHADER_STAGE_VERTEX_BIT;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import org.lwjgl.vulkan.VkPhysicalDeviceMemoryProperties;
import org.oreon.core.context.BaseContext;
import org.oreon.core.model.Mesh;
import org.oreon.core.model.Vertex.VertexLayout;
import org.oreon.core.scenegraph.NodeComponentType;
import org.oreon.core.scenegraph.Renderable;
import org.oreon.core.util.BufferUtil;
import org.oreon.core.util.Constants;
import org.oreon.core.util.ProceduralTexturing;
import org.oreon.core.vk.command.CommandBuffer;
import org.oreon.core.vk.context.DeviceManager.DeviceType;
import org.oreon.core.vk.context.VkContext;
import org.oreon.core.vk.context.VkResources.VkDescriptorName;
import org.oreon.core.vk.descriptor.DescriptorSet;
import org.oreon.core.vk.descriptor.DescriptorSetLayout;
import org.oreon.core.vk.device.LogicalDevice;
import org.oreon.core.vk.memory.VkBuffer;
import org.oreon.core.vk.pipeline.ShaderModule;
import org.oreon.core.vk.pipeline.ShaderPipeline;
import org.oreon.core.vk.pipeline.VkPipeline;
import org.oreon.core.vk.pipeline.VkVertexInput;
import org.oreon.core.vk.scenegraph.VkMeshData;
import org.oreon.core.vk.scenegraph.VkRenderInfo;
import org.oreon.core.vk.util.VkAssimpModelLoader;
import org.oreon.core.vk.util.VkUtil;
import org.oreon.core.vk.wrapper.buffer.VkBufferHelper;
import org.oreon.core.vk.wrapper.buffer.VkUniformBuffer;
import org.oreon.core.vk.wrapper.command.SecondaryDrawIndexedCmdBuffer;
import org.oreon.core.vk.wrapper.pipeline.GraphicsPipeline;

public class Atmosphere extends Renderable{
	
	private VkUniformBuffer uniformBuffer;
	
	public Atmosphere() {
		
		LogicalDevice device = VkContext.INSTANCE.getDeviceManager().getLogicalDevice(DeviceType.MAJOR_GRAPHICS_DEVICE);
		VkPhysicalDeviceMemoryProperties memoryProperties = 
				VkContext.INSTANCE.getDeviceManager().getPhysicalDevice(DeviceType.MAJOR_GRAPHICS_DEVICE).getMemoryProperties();
		
		getWorldTransform().setLocalScaling(Constants.ZFAR*0.5f, Constants.ZFAR*0.5f, Constants.ZFAR*0.5f);
		
		Mesh mesh = VkAssimpModelLoader.loadModel("models/obj/dome", "dome.obj").get(0).getMesh();
		ProceduralTexturing.dome(mesh);
		
		ByteBuffer ubo = memAlloc(Float.BYTES * 16);
		ubo.put(BufferUtil.createByteBuffer(getWorldTransform().getWorldMatrix()));
		ubo.flip();
		
		uniformBuffer = new VkUniformBuffer(device.getHandle(), memoryProperties, ubo);
		
		ShaderModule vertexShader = new ShaderModule(device.getHandle(),
				"shaders/atmosphere/atmosphere.vert.spv", VK_SHADER_STAGE_VERTEX_BIT);
		
		ShaderPipeline graphicsShaderPipeline = new ShaderPipeline(device.getHandle());
	    graphicsShaderPipeline.addShaderModule(vertexShader);
	    graphicsShaderPipeline.createFragmentShader(BaseContext.Companion.getConfig().getAtmosphericScatteringEnable() ?
	    		"shaders/atmosphere/atmospheric_scattering.frag.spv" : "shaders/atmosphere/atmosphere.frag.spv");
	    graphicsShaderPipeline.createShaderPipeline();
	    
	    ShaderPipeline reflectionShaderPipeline = new ShaderPipeline(device.getHandle());
	    reflectionShaderPipeline.addShaderModule(vertexShader);
	    reflectionShaderPipeline.createFragmentShader("shaders/atmosphere/atmosphere_reflection.frag.spv");
	    reflectionShaderPipeline.createShaderPipeline();
	    
	    List<DescriptorSet> descriptorSets = new ArrayList<DescriptorSet>();
		List<DescriptorSetLayout> descriptorSetLayouts = new ArrayList<DescriptorSetLayout>();
		
		DescriptorSetLayout descriptorSetLayout = new DescriptorSetLayout(device.getHandle(), 1);
	    descriptorSetLayout.addLayoutBinding(0, VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER,
	    		VK_SHADER_STAGE_VERTEX_BIT);
	    descriptorSetLayout.create();
	    DescriptorSet descriptorSet = new DescriptorSet(device.getHandle(),
	    		device.getDescriptorPool(Thread.currentThread().getId()).getHandle(),
	    		descriptorSetLayout.getHandlePointer());
	    descriptorSet.updateDescriptorBuffer(uniformBuffer.getHandle(),
	    		ubo.limit(), 0, 0, VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER);
	    
		descriptorSets.add(VkContext.INSTANCE.getCamera().getDescriptorSet());
		descriptorSets.add(descriptorSet);
		descriptorSets.add(VkContext.INSTANCE.getResources().getDescriptors().get(VkDescriptorName.DIRECTIONAL_LIGHT).getDescriptorSet());
		descriptorSetLayouts.add(VkContext.INSTANCE.getCamera().getDescriptorSetLayout());
		descriptorSetLayouts.add(descriptorSetLayout);
		descriptorSetLayouts.add(VkContext.INSTANCE.getResources().getDescriptors().get(VkDescriptorName.DIRECTIONAL_LIGHT).getDescriptorSetLayout());
		
		VkVertexInput vertexInput = new VkVertexInput(VertexLayout.POS);
		
		ByteBuffer vertexBuffer = BufferUtil.createByteBuffer(mesh.getVertices(), VertexLayout.POS);
		ByteBuffer indexBuffer = BufferUtil.createByteBuffer(mesh.getIndices());
		
		int pushConstantsRange = Float.BYTES * 19 + Integer.BYTES * 3;
		
		ByteBuffer pushConstants = memAlloc(pushConstantsRange);
		pushConstants.put(BufferUtil.createByteBuffer(VkContext.INSTANCE.getCamera().getProjectionMatrix()));
		pushConstants.putFloat(BaseContext.Companion.getConfig().getSunRadius());
		pushConstants.putInt(BaseContext.Companion.getConfig().getFrameWidth());
		pushConstants.putInt(BaseContext.Companion.getConfig().getFrameHeight());
		pushConstants.putInt(0);
		pushConstants.putFloat(BaseContext.Companion.getConfig().getAtmosphereBloomFactor());
		pushConstants.putFloat(BaseContext.Companion.getConfig().getHorizonVerticalShift());
		pushConstants.flip();
		
		VkPipeline graphicsPipeline = new GraphicsPipeline(device.getHandle(),
				graphicsShaderPipeline, vertexInput, VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST,
				VkUtil.createLongBuffer(descriptorSetLayouts),
				BaseContext.Companion.getConfig().getFrameWidth(),
				BaseContext.Companion.getConfig().getFrameHeight(),
				VkContext.INSTANCE.getResources().getOffScreenFbo().getRenderPass().getHandle(),
				VkContext.INSTANCE.getResources().getOffScreenFbo().getColorAttachmentCount(),
				BaseContext.Companion.getConfig().getMultisampling_sampleCount(),
				pushConstantsRange, VK_SHADER_STAGE_FRAGMENT_BIT);
		
		VkBuffer vertexBufferObject = VkBufferHelper.createDeviceLocalBuffer(
				device.getHandle(), memoryProperties,
				device.getTransferCommandPool(Thread.currentThread().getId()).getHandle(),
				device.getTransferQueue(),
				vertexBuffer, VK_BUFFER_USAGE_VERTEX_BUFFER_BIT);

        VkBuffer indexBufferObject = VkBufferHelper.createDeviceLocalBuffer(
        		device.getHandle(), memoryProperties,
        		device.getTransferCommandPool(Thread.currentThread().getId()).getHandle(),
        		device.getTransferQueue(),
        		indexBuffer, VK_BUFFER_USAGE_INDEX_BUFFER_BIT);
        
        CommandBuffer mainCommandBuffer = new SecondaryDrawIndexedCmdBuffer(
	    		device.getHandle(),
	    		device.getGraphicsCommandPool(Thread.currentThread().getId()).getHandle(), 
	    		graphicsPipeline.getHandle(), graphicsPipeline.getLayoutHandle(),
	    		VkContext.INSTANCE.getResources().getOffScreenFbo().getFrameBuffer().getHandle(),
	    		VkContext.INSTANCE.getResources().getOffScreenFbo().getRenderPass().getHandle(),
	    		0,
	    		VkUtil.createLongArray(descriptorSets),
	    		vertexBufferObject.getHandle(),
	    		indexBufferObject.getHandle(),
	    		mesh.getIndices().length,
	    		pushConstants, VK_SHADER_STAGE_FRAGMENT_BIT);
	    
        VkMeshData meshData = VkMeshData.builder().vertexBufferObject(vertexBufferObject)
	    		.vertexBuffer(vertexBuffer).indexBufferObject(indexBufferObject).indexBuffer(indexBuffer)
	    		.build();
	    VkRenderInfo mainRenderInfo = VkRenderInfo.builder().commandBuffer(mainCommandBuffer)
	    		.pipeline(graphicsPipeline).descriptorSets(descriptorSets)
	    		.descriptorSetLayouts(descriptorSetLayouts).build();
	    
	    
	    addComponent(NodeComponentType.MESH_DATA, meshData);
	    addComponent(NodeComponentType.MAIN_RENDERINFO, mainRenderInfo);
	    addComponent(NodeComponentType.WIREFRAME_RENDERINFO, mainRenderInfo);
	    
	    if (VkContext.INSTANCE.getResources().getReflectionFbo() != null){
	    	VkPipeline reflectionPipeline = new GraphicsPipeline(device.getHandle(),
					reflectionShaderPipeline, vertexInput, VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST,
					VkUtil.createLongBuffer(descriptorSetLayouts),
					VkContext.INSTANCE.getResources().getReflectionFbo().getWidth(),
					VkContext.INSTANCE.getResources().getReflectionFbo().getHeight(),
					VkContext.INSTANCE.getResources().getReflectionFbo().getRenderPass().getHandle(),
					VkContext.INSTANCE.getResources().getReflectionFbo().getColorAttachmentCount(), 1);
	    	
	    	CommandBuffer reflectionCommandBuffer = new SecondaryDrawIndexedCmdBuffer(
		    		device.getHandle(),
		    		device.getGraphicsCommandPool(Thread.currentThread().getId()).getHandle(), 
		    		reflectionPipeline.getHandle(), reflectionPipeline.getLayoutHandle(),
		    		VkContext.INSTANCE.getResources().getReflectionFbo().getFrameBuffer().getHandle(),
		    		VkContext.INSTANCE.getResources().getReflectionFbo().getRenderPass().getHandle(),
		    		0,
		    		VkUtil.createLongArray(descriptorSets),
		    		vertexBufferObject.getHandle(),
		    		indexBufferObject.getHandle(),
		    		mesh.getIndices().length);
	    	
	    	VkRenderInfo reflectionRenderInfo = VkRenderInfo.builder().commandBuffer(reflectionCommandBuffer)
		    		.pipeline(reflectionPipeline).build();
	    	
	    	addComponent(NodeComponentType.REFLECTION_RENDERINFO, reflectionRenderInfo);
	    }
	    
	    graphicsShaderPipeline.destroy();
	    reflectionShaderPipeline.destroy();
	}
	
	public void update()
	{	
		super.update();
		
		uniformBuffer.mapMemory(BufferUtil.createByteBuffer(getWorldTransform().getWorldMatrix()));
	}
	
	public void shutdown(){
		
		 super.shutdown();
		 uniformBuffer.destroy();
	}

}
