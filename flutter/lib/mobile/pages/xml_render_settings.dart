import 'dart:convert';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

// ---------------------------------------------------------------------------
// Config model
// ---------------------------------------------------------------------------
enum XmlColorScheme { DARK, LIGHT, HIGH_CONTRAST }

class XmlRenderConfig {
  double textSize;
  int elementOpacity;       // 0-255
  double contrast;          // 0.5-2.0
  XmlColorScheme colorScheme;
  bool showClickableIndicators;
  bool showWindowBorders;
  bool showTextContent;
  int frameRate;
  int maxDepth;
  bool skipInvisible;

  XmlRenderConfig({
    this.textSize = 26,
    this.elementOpacity = 160,
    this.contrast = 1.0,
    this.colorScheme = XmlColorScheme.DARK,
    this.showClickableIndicators = true,
    this.showWindowBorders = false,
    this.showTextContent = true,
    this.frameRate = 15,
    this.maxDepth = 20,
    this.skipInvisible = true,
  });

  factory XmlRenderConfig.fromJson(Map<String, dynamic> j) => XmlRenderConfig(
        textSize: (j['textSize'] as num?)?.toDouble() ?? 26,
        elementOpacity: j['elementOpacity'] as int? ?? 160,
        contrast: (j['contrast'] as num?)?.toDouble() ?? 1.0,
        colorScheme: XmlColorScheme.values.firstWhere(
            (e) => e.name == (j['colorScheme'] as String? ?? 'DARK'),
            orElse: () => XmlColorScheme.DARK),
        showClickableIndicators: j['showClickableIndicators'] as bool? ?? true,
        showWindowBorders: j['showWindowBorders'] as bool? ?? false,
        showTextContent: j['showTextContent'] as bool? ?? true,
        frameRate: j['frameRate'] as int? ?? 15,
        maxDepth: j['maxDepth'] as int? ?? 20,
        skipInvisible: j['skipInvisible'] as bool? ?? true,
      );

  Map<String, dynamic> toJson() => {
        'textSize': textSize,
        'elementOpacity': elementOpacity,
        'contrast': contrast,
        'colorScheme': colorScheme.name,
        'showClickableIndicators': showClickableIndicators,
        'showWindowBorders': showWindowBorders,
        'showTextContent': showTextContent,
        'frameRate': frameRate,
        'maxDepth': maxDepth,
        'skipInvisible': skipInvisible,
      };

  XmlRenderConfig copyWith({
    double? textSize,
    int? elementOpacity,
    double? contrast,
    XmlColorScheme? colorScheme,
    bool? showClickableIndicators,
    bool? showWindowBorders,
    bool? showTextContent,
    int? frameRate,
    int? maxDepth,
    bool? skipInvisible,
  }) =>
      XmlRenderConfig(
        textSize: textSize ?? this.textSize,
        elementOpacity: elementOpacity ?? this.elementOpacity,
        contrast: contrast ?? this.contrast,
        colorScheme: colorScheme ?? this.colorScheme,
        showClickableIndicators:
            showClickableIndicators ?? this.showClickableIndicators,
        showWindowBorders: showWindowBorders ?? this.showWindowBorders,
        showTextContent: showTextContent ?? this.showTextContent,
        frameRate: frameRate ?? this.frameRate,
        maxDepth: maxDepth ?? this.maxDepth,
        skipInvisible: skipInvisible ?? this.skipInvisible,
      );
}

// ---------------------------------------------------------------------------
// Controller
// ---------------------------------------------------------------------------
class XmlRenderConfigController {
  static const _channel = MethodChannel('com.carriez.flutter_hbb/xml_config');

  static Future<XmlRenderConfig> load() async {
    try {
      final raw = await _channel.invokeMethod<String>('getConfig');
      if (raw == null) return XmlRenderConfig();
      return XmlRenderConfig.fromJson(jsonDecode(raw));
    } catch (_) {
      return XmlRenderConfig();
    }
  }

  static Future<void> save(XmlRenderConfig cfg) async {
    try {
      await _channel.invokeMethod('updateConfig', jsonEncode(cfg.toJson()));
    } catch (e) {
      debugPrint('XmlRenderConfig save error: $e');
    }
  }

  static Future<XmlRenderConfig> reset() async {
    try {
      final raw = await _channel.invokeMethod<String>('resetConfig');
      if (raw == null) return XmlRenderConfig();
      return XmlRenderConfig.fromJson(jsonDecode(raw));
    } catch (_) {
      return XmlRenderConfig();
    }
  }
}

// ---------------------------------------------------------------------------
// Bottom Sheet
// ---------------------------------------------------------------------------
void showXmlRenderSettings(BuildContext context) {
  showModalBottomSheet(
    context: context,
    isScrollControlled: true,
    backgroundColor: Colors.transparent,
    builder: (_) => const _XmlRenderSettingsSheet(),
  );
}

class _XmlRenderSettingsSheet extends StatefulWidget {
  const _XmlRenderSettingsSheet();

  @override
  State<_XmlRenderSettingsSheet> createState() =>
      _XmlRenderSettingsSheetState();
}

class _XmlRenderSettingsSheetState extends State<_XmlRenderSettingsSheet> {
  XmlRenderConfig _cfg = XmlRenderConfig();
  bool _loading = true;

  // Дебаунс — не шлём на Kotlin при каждом движении слайдера
  DateTime _lastSend = DateTime(0);

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    final cfg = await XmlRenderConfigController.load();
    if (mounted) setState(() { _cfg = cfg; _loading = false; });
  }

  void _update(XmlRenderConfig newCfg) {
    setState(() => _cfg = newCfg);
    // Применяем на лету с дебаунсом 150мс
    final now = DateTime.now();
    _lastSend = now;
    Future.delayed(const Duration(milliseconds: 150), () {
      if (_lastSend == now) XmlRenderConfigController.save(_cfg);
    });
  }

  Future<void> _reset() async {
    final cfg = await XmlRenderConfigController.reset();
    if (mounted) setState(() => _cfg = cfg);
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final cs = theme.colorScheme;

    return DraggableScrollableSheet(
      initialChildSize: 0.75,
      minChildSize: 0.4,
      maxChildSize: 0.95,
      builder: (_, scrollController) => Container(
        decoration: BoxDecoration(
          color: theme.scaffoldBackgroundColor,
          borderRadius: const BorderRadius.vertical(top: Radius.circular(20)),
        ),
        child: _loading
            ? const Center(child: CircularProgressIndicator())
            : Column(children: [
                // ── Handle ──
                Center(
                  child: Container(
                    margin: const EdgeInsets.only(top: 12, bottom: 4),
                    width: 40, height: 4,
                    decoration: BoxDecoration(
                      color: cs.outlineVariant,
                      borderRadius: BorderRadius.circular(2),
                    ),
                  ),
                ),
                // ── Header ──
                Padding(
                  padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 8),
                  child: Row(children: [
                    Icon(Icons.tune, color: cs.primary),
                    const SizedBox(width: 10),
                    Expanded(
                      child: Text('XML Capture Settings',
                          style: theme.textTheme.titleMedium
                              ?.copyWith(fontWeight: FontWeight.bold)),
                    ),
                    TextButton(
                      onPressed: _reset,
                      child: const Text('Reset'),
                    ),
                    IconButton(
                      icon: const Icon(Icons.close),
                      onPressed: () => Navigator.pop(context),
                    ),
                  ]),
                ),
                const Divider(height: 1),
                // ── Content ──
                Expanded(
                  child: ListView(
                    controller: scrollController,
                    padding: const EdgeInsets.fromLTRB(20, 16, 20, 32),
                    children: [
                      _buildColorSchemeSection(theme),
                      const SizedBox(height: 24),
                      _buildVisualSection(theme),
                      const SizedBox(height: 24),
                      _buildLayersSection(theme),
                    ],
                  ),
                ),
              ]),
      ),
    );
  }

  // ── Color Scheme ──
  Widget _buildColorSchemeSection(ThemeData theme) {
    return _Section(
      title: 'Color Scheme',
      icon: Icons.palette_outlined,
      child: Row(children: XmlColorScheme.values.map((scheme) {
        final selected = _cfg.colorScheme == scheme;
        final label = switch (scheme) {
          XmlColorScheme.DARK          => 'Dark',
          XmlColorScheme.LIGHT         => 'Light',
          XmlColorScheme.HIGH_CONTRAST => 'High ▲',
        };
        final previewBg = switch (scheme) {
          XmlColorScheme.DARK          => Colors.grey[900]!,
          XmlColorScheme.LIGHT         => Colors.grey[100]!,
          XmlColorScheme.HIGH_CONTRAST => Colors.black,
        };
        final previewFg = switch (scheme) {
          XmlColorScheme.DARK          => Colors.white,
          XmlColorScheme.LIGHT         => Colors.black,
          XmlColorScheme.HIGH_CONTRAST => Colors.yellow,
        };

        return Expanded(
          child: GestureDetector(
            onTap: () => _update(_cfg.copyWith(colorScheme: scheme)),
            child: AnimatedContainer(
              duration: const Duration(milliseconds: 180),
              margin: const EdgeInsets.only(right: 8),
              padding: const EdgeInsets.symmetric(vertical: 12),
              decoration: BoxDecoration(
                color: selected
                    ? theme.colorScheme.primary
                    : previewBg,
                borderRadius: BorderRadius.circular(10),
                border: Border.all(
                  color: selected
                      ? theme.colorScheme.primary
                      : theme.colorScheme.outlineVariant,
                  width: selected ? 2 : 1,
                ),
              ),
              child: Column(children: [
                Icon(Icons.smartphone,
                    color: selected
                        ? theme.colorScheme.onPrimary
                        : previewFg,
                    size: 20),
                const SizedBox(height: 4),
                Text(label,
                    style: TextStyle(
                      fontSize: 11,
                      fontWeight: FontWeight.w600,
                      color: selected
                          ? theme.colorScheme.onPrimary
                          : previewFg,
                    )),
              ]),
            ),
          ),
        );
      }).toList()),
    );
  }

  // ── Visual sliders ──
  Widget _buildVisualSection(ThemeData theme) {
    return _Section(
      title: 'Visual',
      icon: Icons.tune,
      child: Column(children: [
        _SliderRow(
          label: 'Text Size',
          value: _cfg.textSize,
          min: 12, max: 48, divisions: 18,
          display: '${_cfg.textSize.toInt()}px',
          onChanged: (v) => _update(_cfg.copyWith(textSize: v)),
        ),
        _SliderRow(
          label: 'Opacity',
          value: _cfg.elementOpacity.toDouble(),
          min: 0, max: 255, divisions: 51,
          display: '${(_cfg.elementOpacity / 255 * 100).toInt()}%',
          onChanged: (v) => _update(_cfg.copyWith(elementOpacity: v.toInt())),
        ),
        _SliderRow(
          label: 'Contrast',
          value: _cfg.contrast,
          min: 0.5, max: 2.0, divisions: 15,
          display: '${_cfg.contrast.toStringAsFixed(1)}×',
          onChanged: (v) => _update(_cfg.copyWith(contrast: v)),
        ),
      ]),
    );
  }

  // ── Layers toggles ──
  Widget _buildLayersSection(ThemeData theme) {
    return _Section(
      title: 'Layers',
      icon: Icons.layers_outlined,
      child: Column(children: [
        _Toggle(
          label: 'Show clickable indicators',
          icon: Icons.touch_app_outlined,
          value: _cfg.showClickableIndicators,
          onChanged: (v) =>
              _update(_cfg.copyWith(showClickableIndicators: v)),
        ),
        _Toggle(
          label: 'Show text content',
          icon: Icons.text_fields,
          value: _cfg.showTextContent,
          onChanged: (v) => _update(_cfg.copyWith(showTextContent: v)),
        ),
        _Toggle(
          label: 'Show window borders',
          icon: Icons.border_outer,
          value: _cfg.showWindowBorders,
          onChanged: (v) => _update(_cfg.copyWith(showWindowBorders: v)),
        ),
      ]),
    );
  }
}

// ---------------------------------------------------------------------------
// Reusable sub-widgets
// ---------------------------------------------------------------------------
class _Section extends StatelessWidget {
  final String title;
  final IconData icon;
  final Widget child;

  const _Section({required this.title, required this.icon, required this.child});

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
      Row(children: [
        Icon(icon, size: 16, color: theme.colorScheme.primary),
        const SizedBox(width: 6),
        Text(title,
            style: theme.textTheme.labelLarge?.copyWith(
                color: theme.colorScheme.primary,
                fontWeight: FontWeight.bold)),
      ]),
      const SizedBox(height: 12),
      child,
    ]);
  }
}

class _SliderRow extends StatelessWidget {
  final String label;
  final double value;
  final double min, max;
  final int divisions;
  final String display;
  final ValueChanged<double> onChanged;

  const _SliderRow({
    required this.label,
    required this.value,
    required this.min,
    required this.max,
    required this.divisions,
    required this.display,
    required this.onChanged,
  });

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 4),
      child: Row(children: [
        SizedBox(
          width: 80,
          child: Text(label,
              style: theme.textTheme.bodySmall
                  ?.copyWith(color: theme.colorScheme.onSurfaceVariant)),
        ),
        Expanded(
          child: SliderTheme(
            data: SliderTheme.of(context).copyWith(
              trackHeight: 3,
              thumbShape: const RoundSliderThumbShape(enabledThumbRadius: 8),
            ),
            child: Slider(
              value: value.clamp(min, max),
              min: min, max: max,
              divisions: divisions,
              onChanged: onChanged,
            ),
          ),
        ),
        SizedBox(
          width: 44,
          child: Text(display,
              textAlign: TextAlign.end,
              style: theme.textTheme.bodySmall?.copyWith(
                  fontWeight: FontWeight.w600,
                  color: theme.colorScheme.primary)),
        ),
      ]),
    );
  }
}

class _Toggle extends StatelessWidget {
  final String label;
  final IconData icon;
  final bool value;
  final ValueChanged<bool> onChanged;

  const _Toggle({
    required this.label,
    required this.icon,
    required this.value,
    required this.onChanged,
  });

  @override
  Widget build(BuildContext context) {
    return SwitchListTile(
      visualDensity: VisualDensity.compact,
      contentPadding: EdgeInsets.zero,
      secondary: Icon(icon, size: 20),
      title: Text(label, style: Theme.of(context).textTheme.bodyMedium),
      value: value,
      onChanged: onChanged,
    );
  }
}
