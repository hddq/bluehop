{
  description = "BlueHop - multipoint-like switching for single-connection Bluetooth devices";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
  };

  outputs = { self, nixpkgs }:
    let
      supportedSystems = [ "x86_64-linux" "aarch64-linux" ];
      forAllSystems = nixpkgs.lib.genAttrs supportedSystems;

      devSystem = "x86_64-linux";
      devPkgs = import nixpkgs {
        system = devSystem;
        config = {
          allowUnfree = true;
          android_sdk.accept_license = true;
        };
      };

      androidComposition = devPkgs.androidenv.composeAndroidPackages {
        platformVersions = [ "37.1" ];
        buildToolsVersions = [ "37.0.0" ];
        abiVersions = [ "arm64-v8a" "x86_64" ];
        includeEmulator = false;
        includeSystemImages = false;
        includeNDK = false;
        includeCmake = false;
      };
      androidSdk = androidComposition.androidsdk;
    in {
      packages = forAllSystems (system:
        let
          pkgs = nixpkgs.legacyPackages.${system};
          bluehop = pkgs.callPackage ./nix/package.nix { };
        in {
          inherit bluehop;
          default = bluehop;
        }
      );

      overlays.default = final: prev: {
        bluehop = final.callPackage ./nix/package.nix { };
      };

      nixosModules = rec {
        bluehop = import ./nix/module.nix { inherit self; };
        default = bluehop;
      };

      checks = forAllSystems (system: {
        bluehop = self.packages.${system}.bluehop;
      });

      devShells.${devSystem}.default = devPkgs.mkShell {
        packages = with devPkgs; [
          jdk21
          gradle
          android-tools
          androidSdk
          go_1_27
          git
        ];

        JAVA_HOME = devPkgs.jdk21;
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
