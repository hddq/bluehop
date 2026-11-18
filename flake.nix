{
  description = "BlueHop development shell";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
  };

  outputs = { self, nixpkgs }:
    let
      system = "x86_64-linux";
      pkgs = import nixpkgs {
        inherit system;
        config = {
          allowUnfree = true;
          android_sdk.accept_license = true;
        };
      };

      androidComposition = pkgs.androidenv.composeAndroidPackages {
        platformVersions = [ "37" ];
        buildToolsVersions = [ "37.0.0" ];
        abiVersions = [ "arm64-v8a" "x86_64" ];
        includeEmulator = false;
        includeSystemImages = false;
        includeNDK = false;
        includeCmake = false;
      };
      androidSdk = androidComposition.androidsdk;
    in {
      devShells.${system}.default = pkgs.mkShell {
        packages = with pkgs; [
          jdk21
          gradle
          android-tools
          androidSdk
          go_1_27
          git
        ];

        JAVA_HOME = pkgs.jdk21;
        ANDROID_HOME = "${androidSdk}/libexec/android-sdk";
        ANDROID_SDK_ROOT = "${androidSdk}/libexec/android-sdk";
        GRADLE_OPTS = "-Dorg.gradle.project.android.aapt2FromMavenOverride=${androidSdk}/libexec/android-sdk/build-tools/37.0.0/aapt2";

        shellHook = ''
          export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"
          export GRADLE_USER_HOME="''${GRADLE_USER_HOME:-$HOME/.gradle}"
          export ANDROID_USER_HOME="''${ANDROID_USER_HOME:-$HOME/.android}"

          mkdir -p "$GRADLE_USER_HOME" "$ANDROID_USER_HOME"

          if [ -d android ]; then
            echo "sdk.dir=$ANDROID_HOME" > android/local.properties
          fi
          if [ -d spikes/android ]; then
            echo "sdk.dir=$ANDROID_HOME" > spikes/android/local.properties
          fi
        '';
      };
    };
}
