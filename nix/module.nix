{ self ? null }:
{
  config,
  lib,
  pkgs,
  utils,
  ...
}:

let
  cfg = config.services.bluehop;

  portMatch = builtins.match ".*:([0-9]+)" cfg.listenAddress;
  parsedPort = if portMatch != null then lib.toInt (builtins.head portMatch) else null;

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
    ++ lib.optionals (cfg.storePath != null) [
      "--store"
      (toString cfg.storePath)
    ]
    ++ cfg.extraArgs;
in
{
  imports = [
    (lib.mkRemovedOptionModule [ "services" "bluehop" "user" ] ''
      services.bluehop now runs as a systemd user service (`systemd.user.services.bluehop`)
      so it automatically shares each user's `~/.config/bluehop/peers.json` store.
      Remove `services.bluehop.user` from your configuration.
    '')
    (lib.mkRemovedOptionModule [ "services" "bluehop" "group" ] ''
      services.bluehop now runs as a systemd user service (`systemd.user.services.bluehop`)
      so it automatically shares each user's `~/.config/bluehop/peers.json` store.
      Remove `services.bluehop.group` from your configuration.
    '')
  ];

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
        Path to `peers.json` (`--store`). Defaults to the user's XDG config directory
        (`$XDG_CONFIG_HOME/bluehop/peers.json` or `~/.config/bluehop/peers.json`).
      '';
    };

    openFirewall = lib.mkOption {
      type = lib.types.bool;
      default = false;
      description = "Whether to open the holder TCP port, pairing TCP port (8766), and mDNS UDP port (5353) in the firewall.";
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

    networking.firewall = lib.mkIf cfg.openFirewall {
      allowedTCPPorts = lib.unique [
        parsedPort
        8766
      ];
      allowedUDPPorts = [ 5353 ];
    };

    systemd.user.services.bluehop = {
      description = "BlueHop Bluetooth holder daemon";
      wantedBy = [ "default.target" ];

      unitConfig = {
        ConditionUser = "!@system";
      };

      serviceConfig = {
        ExecStart = "${lib.getExe cfg.package} ${utils.escapeSystemdExecArgs args}";
        Restart = "on-failure";
        RestartSec = 5;

        NoNewPrivileges = true;
        LockPersonality = true;
        MemoryDenyWriteExecute = true;
        RestrictRealtime = true;
        RestrictSUIDSGID = true;
      };
    };
  };
}
