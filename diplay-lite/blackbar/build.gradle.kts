// T3 底部死区遮蔽微应用（系统级，与 DiPlayLite 无关）：开机常驻 100px 黑条。
plugins {
    id("com.android.application")
}

android {
    namespace = "com.shilapi.blackbar"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.shilapi.blackbar"
        minSdk = 17
        targetSdk = 19
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    // 与 app/shared 对齐：显式声明 Java 8 源/目标。
    // 否则在 JDK21 环境下 javac 会按 21 生成 class（v65），旧版 D8(R8 4.0.52) 解析
    // BarService$1 等匿名内部类时报 NPE（Cannot invoke "String.length()" because null）而 dex 失败。
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    lint {
        // targetSdk=19 触发 ExpiredTargetSdkVersion，主工程同款豁免
        checkReleaseBuilds = false
    }
}

dependencies {
}
