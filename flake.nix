{
  description = "Flake for lampepfl/gears";

  inputs = {
    flake-parts.url = "github:hercules-ci/flake-parts";
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
  };

  outputs =
    inputs@{ flake-parts, ... }:
    flake-parts.lib.mkFlake { inherit inputs; } {
      imports = [ ];
      systems = [
        "x86_64-linux"
        "aarch64-linux"
        "aarch64-darwin"
        "x86_64-darwin"
      ];
      perSystem =
        {
          config,
          self',
          inputs',
          pkgs,
          system,
          ...
        }:
        {
          # Per-system attributes can be defined here. The self' and inputs'
          # module parameters provide easy access to attributes of the same
          # system.
          devShells.default = pkgs.mkShell {
            buildInputs = with pkgs; [
              jdk21
              # Scala deps
              (sbt.override { jre = jdk21; })
              # Scala Native deps
              llvm
              clang
              boehmgc
              libunwind
              zlib
              # Scala.js deps
              nodejs_23
              # Dev deps
              metals
              scalafix
              scalafmt
            ];
            shellHook = ''
              export LLVM_BIN=${pkgs.clang}/bin
            '';
          };
        };
      flake = {
        # The usual flake attributes can be defined here, including system-
        # agnostic ones like nixosModule and system-enumerating ones, although
        # those are more easily expressed in perSystem.
      };
    };
}
