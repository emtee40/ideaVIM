package patches.buildTypes

import jetbrains.buildServer.configs.kotlin.v2019_2.*
import jetbrains.buildServer.configs.kotlin.v2019_2.ui.*

/*
This patch script was generated by TeamCity on settings change in UI.
To apply the patch, change the buildType with id = 'PublishVimEngine'
accordingly, and delete the patch script.
*/
changeBuildType(RelativeId("PublishVimEngine")) {
    params {
        expect {
            password("env.ORG_GRADLE_PROJECT_spacePassword", "credentialsJSON:790b4e43-ee83-4184-b81b-678afab60409", display = ParameterDisplay.HIDDEN)
        }
        update {
            password("env.ORG_GRADLE_PROJECT_spacePassword", "credentialsJSON:9b51352f-a9a2-4e19-9d27-2fa2c6dc5ede", display = ParameterDisplay.HIDDEN)
        }
    }
}
