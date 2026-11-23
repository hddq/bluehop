{ self ? null }:
{
  config,
  lib,
  pkgs,
  ...
}:

let
  cfg = config.services.bluehop;
  isSystemUser = cfg.user == "bluehop";

  portMatch = builtins.match ".*:([0-9]+)" cfg.listenAddress;
  parsedPort = if portMatch != null then lib.toInt (builtins.head portMatch) else null;

  resolvedStorePath =
    if cfg.storePath != null then
      cfg.storePath
    else if isSystemUser then
      "/var/lib/bluehop/peers.json"
    else
      null;

  args =
    [
      "serve"
      "--addr"
      cfg.listenAddress
    ]
    ++ lib.optionals (cfg.deviceName != null) [
      "--name"
      cfg.deviceName
    ]
    ++ lib.optionals (resolvedStorePath != null) [
      "--store"
      (toString resolvedStorePath)
    ]
    ++ cfg.extraArgs;
in
{
  options.services.bluehop = {
    enable = lib.mkEnableOption "BlueHop holder daemon";

    package = lib.mkOption {
      type = lib.types.package;
      default =
        if self != null then
          self.packages.${pkgs.stdenv.hostPlatform.system}.bluehop
        else
          pkgs.bluehop;
      defaultText = lib.literalExpression "inputs.bluehop.packages.\${pkgs.stdenv.hostPlatform.system}.bluehop";
      description = "The BlueHop package to use.";
    };

    listenAddress = lib.mkOption {
      type = lib.types.str;
      default = ":8765";
      example = "0.0.0.0:8765";
      description = "TCP address and port for the BlueHop holder HTTP server (`bluehop serve --addr`).";
    };

    deviceName = lib.mkOption {
      type = lib.types.nullOr lib.types.str;
      default = null;
      example = "workstation";
      description = "Local device name reported to paired peers (`--name`). Defaults to the system hostname.";
    };

    storePath = lib.mkOption {
      type = lib.types.nullOr lib.types.str;
      default = null;
      description = ''
        Path to `peers.json` (`--store`). Defaults to `/var/lib/bluehop/peers.json` when
        `services.bluehop.user` is `"bluehop"`, or the user's XDG config directory otherwise.
      '';
    };

    user = lib.mkOption {
      type = lib.types.str;
      default = "bluehop";
      description = "User account under which the BlueHop holder service runs.";
    };

    group = lib.mkOption {
      type = lib.types.str;
      default = if isSystemUser then "bluehop" else config.users.users.${cfg.user}.group;
      defaultText = lib.literalExpression ''"bluehop" (or the user's primary group)'';
      description = "Group under which the BlueHop holder service runs.";
    };

    openFirewall = lib.mkOption {
      type = lib.types.bool;
      default = false;
      description = "Whether to open the holder TCP port and mDNS UDP port (5353) in the firewall.";
    };

    extraArgs = lib.mkOption {
      type = lib.types.listOf lib.types.str;
      default = [ ];
      description = "Additional command-line arguments passed to `bluehop serve`.";
    };
  };

  config = lib.mkIf cfg.enable {
    assertions = [
      {
        assertion = !cfg.openFirewall || (parsedPort != null && parsedPort > 0 && parsedPort <= 65535);
        message = "services.bluehop.openFirewall requires services.bluehop.listenAddress to specify a fixed non-zero port.";
      }
    ];

    environment.systemPackages = [ cfg.package ];

    users.users = lib.mkIf isSystemUser {
      bluehop = {
        isSystemUser = true;
        group = cfg.group;
        description = "BlueHop holder daemon user";
      };
    };

    users.groups = lib.mkIf (cfg.group == "bluehop") {
      bluehop = { };
    };

    networking.firewall = lib.mkIf cfg.openFirewall {
      allowedTCPPorts = [ parsedPort ];
      allowedUDPPorts = [ 5353 ];
    };

    systemd.services.bluehop = {
      description = "BlueHop Bluetooth holder daemon";
      after = [
        "network-online.target"
        "bluetooth.target"
        "dbus.service"
      ];
      wants = [
        "network-online.target"
        "bluetooth.target"
      ];
      requires = [ "dbus.service" ];
      wantedBy = [ "multi-user.target" ];

      serviceConfig = {
        ExecStart = "${lib.getExe cfg.package} ${lib.escapeShellArgs args}";
        User = cfg.user;
        Group = cfg.group;
        Restart = "on-failure";
        RestartSec = 5;

        StateDirectory = lib.mkIf isSystemUser "bluehop";
        StateDirectoryMode = lib.mkIf isSystemUser "0700";

        NoNewPrivileges = true;
        ProtectSystem = if isSystemUser then "strict" else "full";
        ProtectHome = isSystemUser;
        PrivateTmp = true;
        ProtectKernelTunables = true;
        ProtectKernelModules = true;
        ProtectControlGroups = true;
        RestrictSUIDSGID = true;
        LockPersonality = true;
        MemoryDenyWriteExecute = true;
        RestrictRealtime = true;
        CapabilityBoundingSet = "";
        AmbientCapabilities = "";
      };
    };
  };
}
