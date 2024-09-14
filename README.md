# InputWordEngine(安卓输入法引擎For Android inputmethod)
输入单词引擎是一个专为安卓设备提供英文及其他外文语种的输入预测和根据单词补全功能的模块<br>
InputWordEngine is a moudle for Android devices that provides intelligent input prediction and word completion features for English and other foreign languages.<br>
#### 目前支持的语言有英文（英国）、英文（美国）、俄语、西班牙语、德语、法语<br>
#### Currently supported languages are English (UK), English (US), Russian, Spanish, German, French<br>
#### 更多交流加QQ：3323181861<br>
More communication with QQ: 3323181861<br>

这个模块是基于谷歌AOSP中的LatinIME项目改造而来的，我将它集成了一个专门的查询候选及联想模块，使其更容易被其他开发者集成到其应用中<br>
The module is derived from the Google AOSP LatinIME project, with the integration of a specialized query candidate and suggestion module, making it easy for other developers to incorporate into their applications.<br>

相信这个项目是你能看到的第一个免费开箱即用的单词候选输入引擎，
#### 那么给我右上角打一颗星星吧谢谢了<br>
I believe this project is the first free out-of-the-box word input engine you can see
#### Please give me a star in the upper right corner, thank you<br>

随此模块有一个简单的测试demo,可以体验使用.两个输入框分别对应需要查询的单词及循环测试次数<br>
With this module has a simple test demo, you can experience.The two edittext correspond to the word to be queried and the number of cycles to be tested.<br>
在骁龙8Gen1的测试机上测试单词“word”的补充与联想结果，所需要的的时间分别为54ms与4ms<br>
Testing for quering and associating the word "word" on a Snapdragon 8Gen1 test machine resulted in 54ms and 4ms<br>
![image](https://s3.bmp.ovh/imgs/2024/01/13/098497d8ea4743cf.png)
附带一个俄语补全、预测的查询情况<br>
![image](https://i.postimg.cc/pL4cKnjB/screenshot.png)<br>
