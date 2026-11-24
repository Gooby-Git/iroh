#include <vulkan/vulkan.h>
#include <stdio.h>
int main(){ VkApplicationInfo a={VK_STRUCTURE_TYPE_APPLICATION_INFO,0,"p",0,0,0,VK_API_VERSION_1_3};
 VkInstanceCreateInfo ci={VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO,0,0,&a}; VkInstance i; vkCreateInstance(&ci,0,&i);
 uint32_t n=1; VkPhysicalDevice d; vkEnumeratePhysicalDevices(i,&n,&d);
 int f; while(scanf("%d",&f)==1){ VkFormatProperties3 p3={VK_STRUCTURE_TYPE_FORMAT_PROPERTIES_3}; VkFormatProperties2 p={VK_STRUCTURE_TYPE_FORMAT_PROPERTIES_2,&p3};
  vkGetPhysicalDeviceFormatProperties2(d,(VkFormat)f,&p); printf("%d %llu %llu %llu\n",f,(unsigned long long)p3.linearTilingFeatures,(unsigned long long)p3.optimalTilingFeatures,(unsigned long long)p3.bufferFeatures);} return 0;}
