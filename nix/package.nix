{
  lib,
  buildGo127Module,
  go_1_27,
  version ? "0.2.0",
}:

buildGo127Module {
  pname = "bluehop";
  inherit version;

  src = lib.fileset.toSource {
    root = ./..;
    fileset = lib.fileset.unions [
      ../linux
      ../protocol
    ];
  };

  modRoot = "linux";
  subPackages = [ "cmd/bluehop" ];

  vendorHash = "sha256-eI1cdyqtriapd1kWcadoNdHv4FyTawR4KfTWmbxHPiM=";

  # Allow building when nixpkgs' go_1_27 patch version lags behind linux/go.mod.
  postPatch = lib.optionalString (lib.versionOlder go_1_27.version "1.27.4") ''
    sed -i -E 's/^go 1\.27\.[0-9]+$/go ${go_1_27.version}/' linux/go.mod
  '';

  checkPhase = ''
    runHook preCheck
    export GOFLAGS=''${GOFLAGS//-trimpath/}
    buildGoDir test ./...
    runHook postCheck
  '';

  env.CGO_ENABLED = 0;

  ldflags = [
    "-s"
    "-w"
    "-X main.version=v${version}"
  ];

  meta = {
    description = "Multipoint-like switching for single-connection Bluetooth devices";
    homepage = "https://github.com/hddq/bluehop";
    license = lib.licenses.gpl3Only;
    mainProgram = "bluehop";
    platforms = lib.platforms.linux;
  };
}
